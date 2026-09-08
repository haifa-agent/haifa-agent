package io.haifa.agent.store.sqlite.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
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
            "/io/haifa/agent/store/sqlite/migration/haifa-agent-v1.0-init.sql";
    private static final List<String> LEGACY_TABLES = List.of(
            "policy_snapshot",
            "policy_decision",
            "policy_authorization_evidence",
            "approval_grant",
            "project_trust",
            "approval_request_metadata",
            "approval_response_metadata");

    @TempDir
    Path directory;

    @Test
    void standaloneV10ArtifactMatchesNormalMigrationsAndCanBeReopenedByFoundation() throws Exception {
        Path migratedDatabase = directory.resolve("migration.db");
        Path initializedDatabase = directory.resolve("v1-full.db");

        SchemaSnapshot migrated;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                        SqliteStoreConfiguration.defaults(migratedDatabase), Clock.systemUTC());
                Connection connection = foundation.connections().openConnection()) {
            migrated = snapshot(connection);
        }

        initializeArtifact(initializedDatabase);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + initializedDatabase)) {
            assertThat(snapshot(connection)).isEqualTo(migrated);
        }
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                SqliteStoreConfiguration.defaults(initializedDatabase), Clock.systemUTC())) {
            assertThat(foundation).isNotNull();
        }
    }

    @Test
    void sharedSchemaHasMinimalAccessPhysicalFingerprintAndNoLegacyFamily() throws Exception {
        Path database = directory.resolve("shared.db");
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                        SqliteStoreConfiguration.defaults(database), Clock.systemUTC());
                Connection connection = foundation.connections().openConnection()) {
            assertThat(queryStrings(
                            connection, "SELECT name FROM pragma_table_info('coding_workspace_access') ORDER BY cid"))
                    .containsExactly("tenant_id", "principal_type", "principal_id", "workspace_id", "mode");
            assertThat(queryStrings(
                            connection, "SELECT name FROM pragma_table_info('coding_workspace_registry') ORDER BY cid"))
                    .contains("physical_fingerprint")
                    .doesNotContain("fingerprint", "permission", "authorization_ref");
            assertThat(queryStrings(connection, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"))
                    .doesNotContainAnyElementsOf(LEGACY_TABLES);
            assertThat(queryRows(
                            connection, "SELECT version, name, checksum FROM schema_migration ORDER BY version", 3))
                    .hasSize(HaifaAgentStoreMigrations.all().size());
        }
    }

    private static void initializeArtifact(Path database) throws Exception {
        String script;
        try (InputStream input =
                V1FullInitializationContractTest.class.getResourceAsStream(FULL_INITIALIZATION_RESOURCE)) {
            assertThat(input).as("shared V1.0 initialization SQL resource").isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            for (String sql : SqlScriptParser.parse(script)) {
                statement.execute(sql);
            }
        }
    }

    private static SchemaSnapshot snapshot(Connection connection) throws Exception {
        List<String> schema = queryRows(
                connection,
                "SELECT type, name, tbl_name, COALESCE(sql, '<null>') FROM sqlite_master "
                        + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                4);
        List<String> tables = queryStrings(
                connection,
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> foreignKeys = new LinkedHashMap<>();
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
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
            List<String> tableIndexes = queryRows(
                    connection,
                    "SELECT name, \"unique\", origin, partial FROM pragma_index_list('" + table + "') ORDER BY name",
                    4);
            indexes.put(table, tableIndexes);
            for (String index :
                    queryStrings(connection, "SELECT name FROM pragma_index_list('" + table + "') ORDER BY name")) {
                indexColumns.put(
                        index,
                        queryRows(
                                connection,
                                "SELECT seqno, cid, name FROM pragma_index_info('" + index + "') ORDER BY seqno",
                                3));
            }
        }
        List<String> migrations =
                queryRows(connection, "SELECT version, name, checksum FROM schema_migration ORDER BY version", 3);
        return new SchemaSnapshot(schema, columns, foreignKeys, indexes, indexColumns, migrations);
    }

    private static List<String> queryStrings(Connection connection, String sql) throws Exception {
        return queryRows(connection, sql, 1);
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
            List<String> schema,
            Map<String, List<String>> columns,
            Map<String, List<String>> foreignKeys,
            Map<String, List<String>> indexes,
            Map<String, List<String>> indexColumns,
            List<String> migrations) {}
}
