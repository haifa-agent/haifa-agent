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
    void createsAllSharedMigrationsOnceAndAllowsRepeatedStartup() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);

        runner.migrate(HaifaAgentStoreMigrations.all());
        runner.migrate(HaifaAgentStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM schema_migration"))
                    .isEqualTo(HaifaAgentStoreMigrations.all().size());
            assertThat(queryLong(connection, "SELECT applied_at FROM schema_migration WHERE version = 1"))
                    .isEqualTo(SqliteTestSupport.NOW.toEpochMilli());
        }
    }

    @Test
    void cleanBaselineOmitsLegacyDecisionEvidenceGrantAndTrustTables() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK).migrate(HaifaAgentStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(
                            connection,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN "
                                    + "('policy_snapshot', 'policy_decision', 'policy_authorization_evidence', "
                                    + "'approval_grant', 'project_trust', 'approval_request_metadata', "
                                    + "'approval_response_metadata')"))
                    .isZero();
        }
    }

    @Test
    void rejectsPreM7DatabaseWithoutChangingItsSentinelData() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        runner.migrate(List.of(SqliteMigration.fromScript(
                3,
                "policy_approval_security",
                "CREATE TABLE pre_m7_sentinel(value TEXT NOT NULL);"
                        + " INSERT INTO pre_m7_sentinel(value) VALUES ('preserve-me');")));

        assertThatThrownBy(() -> runner.migrate(HaifaAgentStoreMigrations.all()))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.MIGRATION_CHECKSUM_MISMATCH);
        try (Connection connection = connections.openConnection()) {
            assertThat(queryString(connection, "SELECT value FROM pre_m7_sentinel"))
                    .isEqualTo("preserve-me");
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM schema_migration"))
                    .isEqualTo(1);
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
    void preservesExistingInteractionDeadlineWhileMakingTheColumnNullable() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        runner.migrate(throughVersion(8));
        long createdAt = SqliteTestSupport.NOW.toEpochMilli();
        long expiresAt = createdAt + 60_000;
        try (Connection connection = connections.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("INSERT INTO interaction_request (request_id, run_id, tenant_id, principal_id, "
                    + "principal_type, type, prompt, approval, target_type, target_schema_version, "
                    + "target_payload, target_hash, created_at, expires_at, revision, kind, state, "
                    + "expiration_outcome) VALUES ('request-1', 'run-1', 'tenant', 'principal', "
                    + "'user', 'clarification', 'Safe prompt', 0, 'generic', '1', X'00', "
                    + "'hash', " + createdAt + ", " + expiresAt
                    + ", 0, 'clarification', 'PENDING', 'FAIL_RUN')");
        }

        runner.migrate(HaifaAgentStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(
                            connection, "SELECT expires_at FROM interaction_request WHERE request_id = 'request-1'"))
                    .isEqualTo(expiresAt);
            assertThat(queryLong(
                            connection,
                            "SELECT \"notnull\" FROM pragma_table_info('interaction_request') "
                                    + "WHERE name = 'expires_at'"))
                    .isZero();
        }
    }

    @Test
    void startsHumanWaitAccountingAtMigrationForAnExistingWaitingRun() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        runner.migrate(throughVersion(9));
        try (Connection connection = connections.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate(
                    """
                    INSERT INTO run (
                        run_id, schema_version, root_run_id, session_id, tenant_id, principal_id,
                        principal_type, agent_definition_id, agent_definition_version,
                        product_profile_id, product_profile_version, run_type, invocation_mode,
                        depth, objective, budget_max_input_tokens, budget_max_output_tokens,
                        budget_max_cached_input_tokens, budget_max_tool_calls, budget_max_model_calls,
                        budget_max_child_runs, budget_max_cost_currency, budget_max_cost_minor_units,
                        limit_max_iterations, limit_max_depth, limit_max_parallel_children,
                        limit_max_wall_time_millis, limit_max_idle_time_millis, configuration_ref,
                        status, usage_input_tokens, usage_output_tokens, usage_cached_input_tokens,
                        usage_model_calls, usage_tool_calls, usage_child_runs, usage_cost_minor_units,
                        usage_wall_time_millis, waiting_request_id, waiting_request_type,
                        created_at, started_at, updated_at, version
                    ) VALUES (
                        'run-1', '1', 'run-1', 'session-1', 'tenant', 'principal', 'user',
                        'agent', '1.0.0', 'profile', '1', 'chat', 'ROOT', 0, 'objective',
                        100, 100, 100, 10, 10, 1, 'USD', 100, 10, 1, 1, 60000, 10000,
                        'config', 'WAITING_APPROVAL', 0, 0, 0, 0, 0, 0, 0, 0,
                        'approval-1', 'tool-approval', 1000, 1500, 2000, 2
                    )
                    """);
        }

        runner.migrate(HaifaAgentStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(connection, "SELECT human_wait_started_at FROM run WHERE run_id = 'run-1'"))
                    .isEqualTo(2000);
            assertThat(queryLong(connection, "SELECT accumulated_human_wait_millis FROM run WHERE run_id = 'run-1'"))
                    .isZero();
        }
    }

    @Test
    void separatesRunLimitsAndRestoresFrozenBudgetFromConfigurationSnapshot() throws Exception {
        SqliteConnectionFactory connections = initializedConnections();
        SqliteMigrationRunner runner = new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK);
        runner.migrate(throughVersion(10));
        try (Connection connection = connections.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate(
                    """
                    INSERT INTO configuration_snapshot (
                        configuration_ref, schema_version, definition_id, definition_version, profile_id,
                        profile_version, run_type, content_schema_version, content_payload, content_hash, created_at
                    ) VALUES (
                        'config-1', '1', 'agent', '1.0.0', 'profile', '1', 'chat', '1',
                        CAST('{"budget":{"maxToolCalls":0,"maxModelCalls":0,"maxChildRuns":0}}' AS BLOB),
                        'hash', 1000
                    )
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO run (
                        run_id, schema_version, root_run_id, session_id, tenant_id, principal_id,
                        principal_type, agent_definition_id, agent_definition_version,
                        product_profile_id, product_profile_version, run_type, invocation_mode,
                        depth, objective, budget_max_input_tokens, budget_max_output_tokens,
                        budget_max_cached_input_tokens, budget_max_tool_calls, budget_max_model_calls,
                        budget_max_child_runs, budget_max_cost_currency, budget_max_cost_minor_units,
                        limit_max_iterations, limit_max_depth, limit_max_parallel_children,
                        limit_max_wall_time_millis, limit_max_idle_time_millis, configuration_ref,
                        status, usage_input_tokens, usage_output_tokens, usage_cached_input_tokens,
                        usage_model_calls, usage_tool_calls, usage_child_runs, usage_cost_minor_units,
                        usage_wall_time_millis, created_at, updated_at, version
                    ) VALUES (
                        'run-1', '1', 'run-1', 'session-1', 'tenant', 'principal', 'user',
                        'agent', '1.0.0', 'profile', '1', 'chat', 'ROOT', 0, 'objective',
                        0, 0, 0, 32, 64, 8, 'USD', 0, 10, 1, 1, 60000, 10000,
                        'config-1', 'WAITING_APPROVAL', 0, 0, 0, 0, 0, 0, 0, 0, 1000, 1000, 1
                    )
                    """);
        }

        runner.migrate(HaifaAgentStoreMigrations.all());

        try (Connection connection = connections.openConnection()) {
            assertThat(queryLong(connection, "SELECT budget_max_tool_calls FROM run WHERE run_id = 'run-1'"))
                    .isZero();
            assertThat(queryLong(connection, "SELECT budget_max_model_calls FROM run WHERE run_id = 'run-1'"))
                    .isZero();
            assertThat(queryLong(connection, "SELECT budget_max_child_runs FROM run WHERE run_id = 'run-1'"))
                    .isZero();
            assertThat(queryLong(connection, "SELECT limit_max_tool_calls FROM run WHERE run_id = 'run-1'"))
                    .isEqualTo(32);
            assertThat(queryLong(connection, "SELECT limit_max_model_calls FROM run WHERE run_id = 'run-1'"))
                    .isEqualTo(64);
            assertThat(queryLong(connection, "SELECT limit_max_child_runs FROM run WHERE run_id = 'run-1'"))
                    .isEqualTo(8);
        }
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

    private static String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static List<SqliteMigration> throughVersion(long version) {
        return HaifaAgentStoreMigrations.all().stream()
                .filter(migration -> migration.version() <= version)
                .toList();
    }
}
