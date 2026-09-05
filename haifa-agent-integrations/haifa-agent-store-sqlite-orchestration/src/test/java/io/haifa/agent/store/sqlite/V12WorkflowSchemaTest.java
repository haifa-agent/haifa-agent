package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import io.haifa.agent.store.sqlite.orchestration.migration.WorkflowStoreMigrations;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V12WorkflowSchemaTest {
    @TempDir
    Path directory;

    @Test
    void createsNormalizedWorkflowFactsAndRequiredIndexes() throws Exception {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(SqliteWorkflowStoreTestSupport.configuration(directory));
        connections.initialize();
        new SqliteMigrationRunner(connections, SqliteWorkflowStoreTestSupport.CLOCK)
                .migrate(WorkflowStoreMigrations.complete()
                        .subList(0, RuntimeStoreMigrations.all().size() + 1));
        try (Connection connection = connections.openConnection()) {
            assertThat(names(
                            connection, "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'workflow_%'"))
                    .containsExactlyInAnyOrder(
                            "workflow_run",
                            "workflow_node_attempt",
                            "workflow_wait",
                            "workflow_checkpoint",
                            "workflow_event",
                            "workflow_outbox",
                            "workflow_command");
            assertThat(names(
                            connection, "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE '%workflow%'"))
                    .contains(
                            "idx_workflow_run_recovery",
                            "uq_workflow_active_attempt",
                            "idx_workflow_attempt_agent_run",
                            "idx_workflow_outbox_pending");
            assertThat(foreignKeys(connection, "workflow_node_attempt"))
                    .contains("agent_run_id->run.run_id", "workflow_run_id->workflow_run.workflow_run_id");
            assertThat(columns(connection, "workflow_run"))
                    .contains(
                            "definition_digest",
                            "adapter_coordinate",
                            "adapter_version",
                            "adapter_configuration_digest",
                            "state_codec_version",
                            "state_payload",
                            "state_hash",
                            "control_payload",
                            "control_hash");
            assertThat(columns(connection, "workflow_run"))
                    .noneMatch(column -> column.contains("langgraph") || column.contains("provider_object"));
        }
    }

    private static Set<String> names(Connection connection, String sql) throws Exception {
        Set<String> names = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        return names(connection, "SELECT name FROM pragma_table_info('" + table + "')");
    }

    private static Set<String> foreignKeys(Connection connection, String table) throws Exception {
        Set<String> keys = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_list('" + table + "')")) {
            while (rows.next()) {
                keys.add(rows.getString("from") + "->" + rows.getString("table") + "." + rows.getString("to"));
            }
        }
        return keys;
    }
}
