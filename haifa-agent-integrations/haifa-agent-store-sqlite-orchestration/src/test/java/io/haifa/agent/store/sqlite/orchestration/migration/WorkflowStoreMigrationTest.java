package io.haifa.agent.store.sqlite.orchestration.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigration;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowStoreMigrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void upgradesRuntimeV11ToWorkflowV12WithoutChangingRuntimeHistory() throws Exception {
        SqliteConnectionFactory connections = connections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, CLOCK);
        runner.migrate(RuntimeStoreMigrations.all());
        Map<Long, AppliedMigration> runtimeHistory = history(connections);

        assertThat(runtimeHistory).hasSize(RuntimeStoreMigrations.all().size());
        assertThat(runtimeHistory.keySet()).contains(RuntimeStoreMigrations.currentVersion());
        assertThat(tableCount(connections, "workflow_run")).isZero();

        List<SqliteMigration> throughWorkflowV12 = WorkflowStoreMigrations.complete()
                .subList(0, RuntimeStoreMigrations.all().size() + 1);
        runner.migrate(throughWorkflowV12);

        Map<Long, AppliedMigration> v12History = history(connections);
        assertThat(v12History).containsAllEntriesOf(runtimeHistory);
        assertThat(v12History.get(RuntimeStoreMigrations.currentVersion() + 1).name())
                .isEqualTo("workflow_recovery");
        assertThat(tableCount(connections, "workflow_run")).isOne();

        runner.migrate(WorkflowStoreMigrations.complete());
        assertThat(history(connections)).containsAllEntriesOf(runtimeHistory);
        assertThat(tableCount(connections, "workflow_subgraph_instance")).isOne();
    }

    @Test
    void catalogStartsImmediatelyAfterRuntimeAndDerivesItsCurrentVersion() {
        assertThat(WorkflowStoreMigrations.extensions().getFirst().version())
                .isEqualTo(RuntimeStoreMigrations.currentVersion() + 1);
        assertThat(WorkflowStoreMigrations.currentVersion())
                .isEqualTo(WorkflowStoreMigrations.complete().getLast().version());
    }

    private SqliteConnectionFactory connections() {
        Path database = directory.resolve("runtime.db").toAbsolutePath();
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(new SqliteStoreConfiguration(database, 1_250, 8_192));
        connections.initialize();
        return connections;
    }

    private static Map<Long, AppliedMigration> history(SqliteConnectionFactory connections) throws Exception {
        Map<Long, AppliedMigration> history = new LinkedHashMap<>();
        try (Connection connection = connections.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT version,name,checksum FROM schema_migration ORDER BY version")) {
            while (rows.next()) {
                history.put(
                        rows.getLong("version"),
                        new AppliedMigration(rows.getString("name"), rows.getString("checksum")));
            }
        }
        return Map.copyOf(history);
    }

    private static long tableCount(SqliteConnectionFactory connections, String table) throws Exception {
        try (Connection connection = connections.openConnection();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private record AppliedMigration(String name, String checksum) {}
}
