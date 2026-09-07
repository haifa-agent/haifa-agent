package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.migration.SqlScriptParser;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1FullInitializationContractTest {
    private static final String FULL_INITIALIZATION_RESOURCE =
            "/io/haifa/agent/application/project/persistence/haifa-agent-ca-v1.0-init.sql";

    @TempDir
    Path directory;

    @Test
    void freshCodingSchemaContainsTheMinimalWorkspaceAccessRelation() throws Exception {
        Path database = directory.resolve("migration.db");

        try (SqliteConnectionFactory connections = migrated(database);
                Connection connection = connections.openConnection()) {
            List<String> columns = queryStrings(
                    connection, "SELECT name FROM pragma_table_info('coding_workspace_access') ORDER BY cid");

            assertThat(columns).containsExactly("tenant_id", "principal_type", "principal_id", "workspace_id", "mode");
        }
    }

    @Test
    void workspaceAccessSeparatesOwnersAndAtomicallyReplacesOrRevokesOneMode() throws Exception {
        Path database = directory.resolve("workspace-access.db");

        try (SqliteConnectionFactory connections = migrated(database);
                Connection connection = connections.openConnection()) {
            assertThat(queryStrings(
                            connection,
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='coding_workspace_access'"))
                    .containsExactly("coding_workspace_access");

            execute(connection, "INSERT INTO coding_workspace_access VALUES ('tenant-a','USER','alice','ws-1','READ')");
            execute(
                    connection,
                    "INSERT INTO coding_workspace_access VALUES ('tenant-a','USER','bob','ws-1','DEVELOP')");
            assertThat(queryStrings(
                            connection,
                            "SELECT principal_id || ':' || mode FROM coding_workspace_access ORDER BY principal_id"))
                    .containsExactly("alice:READ", "bob:DEVELOP");

            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO coding_workspace_access VALUES ('tenant-a','USER','alice','ws-1','DEVELOP') "
                            + "ON CONFLICT(tenant_id,principal_type,principal_id,workspace_id) "
                            + "DO UPDATE SET mode=excluded.mode");
            connection.commit();
            assertThat(queryStrings(
                            connection,
                            "SELECT mode FROM coding_workspace_access WHERE tenant_id='tenant-a' "
                                    + "AND principal_type='USER' AND principal_id='alice' AND workspace_id='ws-1'"))
                    .containsExactly("DEVELOP");

            execute(
                    connection,
                    "DELETE FROM coding_workspace_access WHERE tenant_id='tenant-a' AND principal_type='USER' "
                            + "AND principal_id='alice' AND workspace_id='ws-1'");
            connection.commit();
            assertThat(queryStrings(
                            connection,
                            "SELECT principal_id || ':' || mode FROM coding_workspace_access ORDER BY principal_id"))
                    .containsExactly("bob:DEVELOP");
        }
    }

    @Test
    void standaloneV10InitializationProducesTheSameSchemaAsNormalMigrations() throws Exception {
        Path migratedDatabase = directory.resolve("migration.db");
        Path initializedDatabase = directory.resolve("v1-full.db");

        SchemaSnapshot migrated;
        try (SqliteConnectionFactory connections = migrated(migratedDatabase);
                Connection connection = connections.openConnection()) {
            migrated = snapshot(connection);
        }

        String script;
        try (InputStream input =
                V1FullInitializationContractTest.class.getResourceAsStream(FULL_INITIALIZATION_RESOURCE)) {
            assertThat(input).as("CA V1.0 full initialization SQL resource").isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + initializedDatabase)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                for (String sql : SqlScriptParser.parse(script)) {
                    statement.execute(sql);
                }
            }
            assertThat(snapshot(connection)).isEqualTo(migrated);
        }
    }

    private static SqliteConnectionFactory migrated(Path database) {
        var connections =
                new SqliteConnectionFactory(new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192));
        connections.initialize();
        new SqliteMigrationRunner(connections, Clock.systemUTC()).migrate(ProjectApplicationMigrations.all());
        return connections;
    }

    private static SchemaSnapshot snapshot(Connection connection) throws Exception {
        List<String> tables = queryStrings(
                connection,
                "SELECT name FROM sqlite_master WHERE type='table' "
                        + "AND name NOT LIKE 'sqlite_%' AND name <> 'schema_migration' ORDER BY name");
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> foreignKeys = new LinkedHashMap<>();
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        for (String table : tables) {
            columns.put(
                    table,
                    queryRows(
                            connection,
                            "SELECT cid, name, type, \"notnull\", COALESCE(dflt_value, '<null>'), pk "
                                    + "FROM pragma_table_info('" + table + "') ORDER BY cid",
                            6));
            foreignKeys.put(
                    table,
                    queryRows(
                            connection,
                            "SELECT id, seq, \"table\", \"from\", \"to\", on_update, on_delete, match "
                                    + "FROM pragma_foreign_key_list('" + table + "') ORDER BY id, seq",
                            8));
            indexes.put(
                    table,
                    queryRows(
                            connection,
                            "SELECT name, \"unique\", origin, partial FROM pragma_index_list('" + table + "') "
                                    + "WHERE name NOT LIKE 'sqlite_autoindex_%' ORDER BY name",
                            4));
        }
        return new SchemaSnapshot(tables, columns, foreignKeys, indexes);
    }

    private static List<String> queryStrings(Connection connection, String sql) throws Exception {
        return queryRows(connection, sql, 1);
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> queryRows(Connection connection, String sql, int columns) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    if (column > 1) row.append('|');
                    row.append(result.getString(column));
                }
                values.add(row.toString());
            }
        }
        return List.copyOf(values);
    }

    private record SchemaSnapshot(
            List<String> tables,
            Map<String, List<String>> columns,
            Map<String, List<String>> foreignKeys,
            Map<String, List<String>> indexes) {}
}
