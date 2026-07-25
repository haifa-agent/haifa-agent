package io.haifa.agent.store.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

public final class SqliteConnectionFactory {
    private final SqliteStoreConfiguration configuration;
    private volatile boolean initialized;

    public SqliteConnectionFactory(SqliteStoreConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        try (Connection connection = openRawConnection();
                Statement statement = connection.createStatement()) {
            String journalMode;
            try (ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next()) {
                    throw pragmaFailure("SQLite did not return a journal mode");
                }
                journalMode = result.getString(1);
            }
            if (!"wal".equals(journalMode.toLowerCase(Locale.ROOT))) {
                throw pragmaFailure("SQLite WAL mode is unavailable");
            }
            validateConnectionPragmas(connection, true);
            initialized = true;
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to initialize SQLite database", exception);
        }
    }

    public Connection openConnection() {
        if (!initialized) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is not initialized");
        }
        Connection connection = openRawConnection();
        try {
            validateConnectionPragmas(connection, true);
            return connection;
        } catch (RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public SqliteStoreConfiguration configuration() {
        return configuration;
    }

    private Connection openRawConnection() {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + configuration.databasePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + configuration.busyTimeoutMillis());
            }
            return connection;
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to open SQLite database", exception);
        }
    }

    private void validateConnectionPragmas(Connection connection, boolean requireWal) {
        try {
            if (queryLong(connection, "PRAGMA foreign_keys") != 1L) {
                throw pragmaFailure("SQLite foreign key enforcement is disabled");
            }
            if (queryLong(connection, "PRAGMA busy_timeout") != configuration.busyTimeoutMillis()) {
                throw pragmaFailure("SQLite busy timeout does not match the configured value");
            }
            if (requireWal) {
                String mode = queryString(connection, "PRAGMA journal_mode");
                if (!"wal".equals(mode.toLowerCase(Locale.ROOT))) {
                    throw pragmaFailure("SQLite connection is not using WAL");
                }
            }
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.PRAGMA_VALIDATION_FAILED,
                    "Unable to validate SQLite connection settings",
                    exception);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("PRAGMA returned no row");
            }
            return result.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("PRAGMA returned no row");
            }
            return Objects.requireNonNull(result.getString(1), "PRAGMA value must not be null");
        }
    }

    private static SqliteStoreException pragmaFailure(String message) {
        return new SqliteStoreException(SqliteStoreFailure.PRAGMA_VALIDATION_FAILED, message);
    }
}
