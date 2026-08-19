package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V9WorkflowSubgraphSchemaTest {
    @TempDir
    Path directory;

    @Test
    void createsImmutableParentChildIdentityAndRecoveryIndex() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
                Connection connection = foundation.connections().openConnection()) {
            assertThat(columns(connection, "workflow_subgraph_instance"))
                    .containsExactlyInAnyOrder(
                            "child_workflow_run_id",
                            "parent_workflow_run_id",
                            "parent_node_id",
                            "parent_node_attempt",
                            "active");
            assertThat(foreignKeys(connection, "workflow_subgraph_instance"))
                    .containsExactlyInAnyOrder(
                            "child_workflow_run_id->workflow_run.workflow_run_id",
                            "parent_workflow_run_id->workflow_run.workflow_run_id");
            assertThat(names(
                            connection,
                            "SELECT name FROM sqlite_master WHERE type='index' "
                                    + "AND name='idx_workflow_subgraph_parent'"))
                    .containsExactly("idx_workflow_subgraph_parent");
        }
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

    private static Set<String> names(Connection connection, String sql) throws Exception {
        Set<String> names = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }
}
