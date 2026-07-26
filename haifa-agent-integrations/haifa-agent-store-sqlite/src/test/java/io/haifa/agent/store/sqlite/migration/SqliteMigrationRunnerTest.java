package io.haifa.agent.store.sqlite.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.store.sqlite.SqliteTestSupport;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMigrationRunnerTest {
    @TempDir
    Path directory;

    @Test
    void createsV1OnceAndAllowsRepeatedStartup() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);

        runner.migrate(RuntimeStoreMigrations.all());
        runner.migrate(RuntimeStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM schema_migration"))
                    .isEqualTo(3);
            assertThat(queryLong(connection, "SELECT applied_at FROM schema_migration WHERE version = 1"))
                    .isEqualTo(SqliteTestSupport.NOW.toEpochMilli());
        }
    }

    @Test
    void rejectsChecksumDrift() {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        runner.migrate(List.of(SqliteMigration.fromScript(1, "test", "CREATE TABLE drift_test(id INTEGER);")));

        assertThatThrownBy(() -> runner.migrate(List.of(
                        SqliteMigration.fromScript(1, "test", "CREATE TABLE drift_test(id INTEGER, changed TEXT);"))))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.MIGRATION_CHECKSUM_MISMATCH);
    }

    @Test
    void rollsBackWholeMigrationWhenAnyStatementFails() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        SqliteMigration broken = SqliteMigration.fromScript(
                1,
                "broken",
                """
                CREATE TABLE partial_table(id INTEGER);
                INSERT INTO missing_table(id) VALUES (1);
                """);

        assertThatThrownBy(() -> runner.migrate(List.of(broken)))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.MIGRATION_FAILED);

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(
                            connection,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='partial_table'"))
                    .isZero();
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM schema_migration"))
                    .isZero();
        }
    }

    private SqliteConnectionFactory initializedConnections() {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(SqliteTestSupport.configuration(directory));
        connections.initialize();
        return connections;
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
