package io.haifa.agent.store.sqlite.migration;

import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SqliteMigrationRunner {
    private static final String CREATE_MIGRATION_TABLE =
            """
            CREATE TABLE IF NOT EXISTS schema_migration (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                checksum TEXT NOT NULL,
                applied_at INTEGER NOT NULL CHECK (applied_at >= 0)
            )
            """;

    private final SqliteConnectionFactory connections;
    private final Clock clock;

    public SqliteMigrationRunner(SqliteConnectionFactory connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void migrate(List<SqliteMigration> migrations) {
        List<SqliteMigration> ordered = validateMigrations(migrations);
        try (Connection connection = connections.openConnection()) {
            createMigrationTable(connection);
            Map<Long, AppliedMigration> applied = readApplied(connection);
            validateApplied(ordered, applied);
            for (SqliteMigration migration : ordered) {
                if (!applied.containsKey(migration.version())) {
                    apply(connection, migration);
                }
            }
        } catch (SqliteStoreException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw failure("Unable to execute SQLite migrations", exception);
        }
    }

    private static List<SqliteMigration> validateMigrations(List<SqliteMigration> migrations) {
        List<SqliteMigration> ordered = Objects.requireNonNull(migrations, "migrations must not be null").stream()
                .sorted(java.util.Comparator.comparingLong(SqliteMigration::version))
                .toList();
        Set<Long> versions = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (SqliteMigration migration : ordered) {
            Objects.requireNonNull(migration, "migration must not be null");
            if (!versions.add(migration.version()) || !names.add(migration.name())) {
                throw failure("Migration versions and names must be unique", null);
            }
        }
        return ordered;
    }

    private static void createMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_MIGRATION_TABLE);
        }
    }

    private static Map<Long, AppliedMigration> readApplied(Connection connection) throws SQLException {
        Map<Long, AppliedMigration> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT version, name, checksum FROM schema_migration ORDER BY version")) {
            while (result.next()) {
                applied.put(
                        result.getLong("version"),
                        new AppliedMigration(result.getString("name"), result.getString("checksum")));
            }
        }
        return applied;
    }

    private static void validateApplied(List<SqliteMigration> migrations, Map<Long, AppliedMigration> applied) {
        Map<Long, SqliteMigration> expected = new HashMap<>();
        migrations.forEach(migration -> expected.put(migration.version(), migration));
        for (Map.Entry<Long, AppliedMigration> entry : applied.entrySet()) {
            SqliteMigration migration = expected.get(entry.getKey());
            if (migration == null
                    || !migration.name().equals(entry.getValue().name())
                    || !migration.checksum().equals(entry.getValue().checksum())) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_CHECKSUM_MISMATCH,
                        "Applied SQLite migration does not match the bundled migration set");
            }
        }
    }

    private void apply(Connection connection, SqliteMigration migration) {
        boolean transactionStarted = false;
        try {
            executeControl(connection, "BEGIN IMMEDIATE");
            transactionStarted = true;
            try (Statement statement = connection.createStatement()) {
                for (String sql : migration.statements()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migration(version, name, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, migration.version());
                statement.setString(2, migration.name());
                statement.setString(3, migration.checksum());
                statement.setLong(4, clock.millis());
                if (statement.executeUpdate() != 1) {
                    throw failure("Migration metadata insert affected an unexpected row count", null);
                }
            }
            executeControl(connection, "COMMIT");
        } catch (RuntimeException | SQLException exception) {
            if (transactionStarted) {
                rollback(connection, exception);
            }
            if (exception instanceof SqliteStoreException storeException) {
                throw storeException;
            }
            throw failure("SQLite migration failed", exception);
        }
    }

    private static void executeControl(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            executeControl(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static SqliteStoreException failure(String message, Throwable cause) {
        return cause == null
                ? new SqliteStoreException(SqliteStoreFailure.MIGRATION_FAILED, message)
                : new SqliteStoreException(SqliteStoreFailure.MIGRATION_FAILED, message, cause);
    }

    private record AppliedMigration(String name, String checksum) {}
}
