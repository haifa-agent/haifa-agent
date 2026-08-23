package io.haifa.agent.store.sqlite;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1SchemaMetadataTest {
    @TempDir
    Path directory;

    @Test
    void createsCompleteV1TablesAndColumns() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        try (Connection connection = foundation.connections().openConnection()) {
            assertThat(userTables(connection)).containsAll(EXPECTED_COLUMNS.keySet());
            EXPECTED_COLUMNS.forEach((table, expected) -> assertThat(columns(connection, table))
                    .as("columns for %s", table)
                    .isEqualTo(expected));
        }
    }

    @Test
    void createsRequiredIndexesForeignKeysAndBlobIntegrityTriples() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        try (Connection connection = foundation.connections().openConnection()) {
            assertThat(indexes(connection)).containsAll(EXPECTED_INDEXES);
            assertThat(indexSql(connection, "uq_active_attempt_per_run"))
                    .contains("UNIQUE INDEX", "WHERE status IN ('QUEUED', 'RUNNING')");
            assertThat(foreignKeys(connection)).containsAll(EXPECTED_FOREIGN_KEYS);

            for (Map.Entry<String, Set<String>> table : EXPECTED_COLUMNS.entrySet()) {
                Map<String, String> typedColumns = typedColumns(connection, table.getKey());
                typedColumns.forEach((column, type) -> {
                    if ("BLOB".equals(type)) {
                        String prefix = column.equals("payload")
                                ? "payload"
                                : column.substring(0, column.length() - "_payload".length());
                        assertThat(table.getValue())
                                .as("schema version adjacent to %s.%s", table.getKey(), column)
                                .contains(prefix + "_schema_version");
                        assertThat(table.getValue())
                                .as("hash adjacent to %s.%s", table.getKey(), column)
                                .contains(prefix + "_hash");
                    }
                });
            }
        }
    }

    private static Set<String> userTables(Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static Set<String> columns(Connection connection, String table) {
        return typedColumns(connection, table).keySet();
    }

    private static Map<String, String> typedColumns(Connection connection, String table) {
        Map<String, String> columns = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                columns.put(result.getString("name"), result.getString("type"));
            }
            return Map.copyOf(columns);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to inspect table " + table, exception);
        }
    }

    private static Set<String> indexes(Connection connection) throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")) {
            while (result.next()) {
                indexes.add(result.getString(1));
            }
        }
        return indexes;
    }

    private static String indexSql(Connection connection, String name) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT sql FROM sqlite_master WHERE type='index' AND name='" + name + "'")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Set<String> foreignKeys(Connection connection) throws Exception {
        Set<String> keys = new HashSet<>();
        for (String table : EXPECTED_COLUMNS.keySet()) {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("PRAGMA foreign_key_list('" + table + "')")) {
                while (result.next()) {
                    keys.add(table + "." + result.getString("from") + "->" + result.getString("table") + "."
                            + result.getString("to"));
                }
            }
        }
        return keys;
    }

    private static Set<String> set(String... values) {
        return Set.of(values);
    }

    private static final Map<String, Set<String>> EXPECTED_COLUMNS = Map.ofEntries(
            entry("schema_migration", set("version", "name", "checksum", "applied_at")),
            entry(
                    "session",
                    set(
                            "session_id",
                            "schema_version",
                            "tenant_id",
                            "owner_principal_id",
                            "owner_principal_type",
                            "project_id",
                            "scope",
                            "status",
                            "created_at",
                            "updated_at",
                            "closed_at",
                            "version",
                            "metadata_schema_version",
                            "metadata_payload",
                            "metadata_hash")),
            entry(
                    "configuration_snapshot",
                    set(
                            "configuration_ref",
                            "schema_version",
                            "definition_id",
                            "definition_version",
                            "profile_id",
                            "profile_version",
                            "run_type",
                            "content_schema_version",
                            "content_payload",
                            "content_hash",
                            "content_payload_hash",
                            "created_at")),
            entry(
                    "run",
                    set(
                            "run_id",
                            "schema_version",
                            "root_run_id",
                            "parent_run_id",
                            "session_id",
                            "project_id",
                            "tenant_id",
                            "principal_id",
                            "principal_type",
                            "agent_definition_id",
                            "agent_definition_version",
                            "product_profile_id",
                            "product_profile_version",
                            "run_type",
                            "invocation_mode",
                            "depth",
                            "objective",
                            "budget_max_input_tokens",
                            "budget_max_output_tokens",
                            "budget_max_cached_input_tokens",
                            "budget_max_tool_calls",
                            "budget_max_model_calls",
                            "budget_max_child_runs",
                            "budget_max_cost_currency",
                            "budget_max_cost_minor_units",
                            "limit_max_iterations",
                            "limit_max_depth",
                            "limit_max_parallel_children",
                            "limit_max_wall_time_millis",
                            "limit_max_idle_time_millis",
                            "configuration_ref",
                            "status",
                            "usage_input_tokens",
                            "usage_output_tokens",
                            "usage_cached_input_tokens",
                            "usage_model_calls",
                            "usage_tool_calls",
                            "usage_child_runs",
                            "usage_cost_minor_units",
                            "usage_wall_time_millis",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "error_schema_version",
                            "error_payload",
                            "error_hash",
                            "waiting_request_id",
                            "waiting_request_type",
                            "termination_reason",
                            "termination_description",
                            "created_at",
                            "queued_at",
                            "started_at",
                            "suspended_at",
                            "resumed_at",
                            "completed_at",
                            "updated_at",
                            "version")),
            entry(
                    "execution_attempt",
                    set(
                            "attempt_id",
                            "schema_version",
                            "run_id",
                            "attempt_number",
                            "status",
                            "created_at",
                            "started_at",
                            "heartbeat_at",
                            "completed_at",
                            "worker_id",
                            "resumed_from_checkpoint_id",
                            "error_schema_version",
                            "error_payload",
                            "error_hash",
                            "version")),
            entry(
                    "session_message",
                    set(
                            "message_id",
                            "session_id",
                            "run_id",
                            "parent_message_id",
                            "sequence",
                            "role",
                            "status",
                            "visibility",
                            "content_schema_version",
                            "content_payload",
                            "content_hash",
                            "metadata_schema_version",
                            "metadata_payload",
                            "metadata_hash",
                            "created_at")),
            entry(
                    "step",
                    set(
                            "step_id",
                            "schema_version",
                            "run_id",
                            "parent_step_id",
                            "branch_id",
                            "type",
                            "sequence",
                            "status",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "error_schema_version",
                            "error_payload",
                            "error_hash",
                            "created_at",
                            "started_at",
                            "completed_at",
                            "version")),
            entry(
                    "tool_call",
                    set(
                            "tool_call_id",
                            "schema_version",
                            "run_id",
                            "step_id",
                            "provider_correlation_id",
                            "idempotency_key",
                            "tool_name",
                            "tool_version",
                            "arguments_schema_version",
                            "arguments_payload",
                            "arguments_hash",
                            "status",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "error_schema_version",
                            "error_payload",
                            "error_hash",
                            "requested_at",
                            "started_at",
                            "completed_at",
                            "version")),
            entry(
                    "plan",
                    set(
                            "plan_id",
                            "schema_version",
                            "run_id",
                            "objective",
                            "items_schema_version",
                            "items_payload",
                            "items_hash",
                            "revision",
                            "created_at",
                            "updated_at")),
            entry("run_output", set("run_id", "output_schema_version", "output_payload", "output_hash", "updated_at")),
            entry(
                    "checkpoint",
                    set(
                            "checkpoint_id",
                            "run_id",
                            "step_id",
                            "type",
                            "status",
                            "sequence",
                            "payload_store_type",
                            "payload_location",
                            "payload_schema_id",
                            "payload_schema_version",
                            "state_hash",
                            "created_at")),
            entry(
                    "checkpoint_payload",
                    set(
                            "checkpoint_id",
                            "state_schema_version",
                            "state_payload",
                            "state_hash",
                            "payload_hash",
                            "created_at")),
            entry(
                    "runtime_event",
                    set(
                            "event_id",
                            "run_id",
                            "sequence",
                            "type",
                            "event_schema_version",
                            "data_schema_version",
                            "data_payload",
                            "data_hash",
                            "correlation_id",
                            "causation_id",
                            "occurred_at")),
            entry("runtime_event_stream", set("run_id", "head_sequence", "earliest_sequence", "updated_at")),
            entry(
                    "outbox",
                    set(
                            "event_id",
                            "run_id",
                            "sequence",
                            "type",
                            "payload_schema_version",
                            "payload",
                            "payload_hash",
                            "created_at",
                            "published_at")),
            entry("outbox_consumer", set("consumer_id", "event_id", "consumed_at")),
            entry(
                    "idempotency",
                    set(
                            "caller_scope",
                            "operation",
                            "idempotency_key",
                            "target_type",
                            "target_id",
                            "request_digest",
                            "run_id",
                            "command_applied",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "created_at",
                            "updated_at",
                            "expires_at")),
            entry(
                    "memory_selection",
                    set(
                            "run_id",
                            "retrieval_policy_version",
                            "query_digest",
                            "memories_schema_version",
                            "memories_payload",
                            "memories_hash",
                            "updated_at")),
            entry(
                    "model_continuation",
                    set(
                            "continuation_id",
                            "continuation_version",
                            "continuation_digest",
                            "byte_length",
                            "assistant_message_id",
                            "run_id",
                            "session_id",
                            "model_call_id",
                            "provider_id",
                            "model_id",
                            "configuration_digest",
                            "tool_correlations_schema_version",
                            "tool_correlations_payload",
                            "tool_correlations_hash",
                            "protection_version",
                            "nonce_schema_version",
                            "nonce_payload",
                            "nonce_hash",
                            "ciphertext_schema_version",
                            "ciphertext_payload",
                            "ciphertext_hash",
                            "created_at")),
            entry(
                    "skill_activation",
                    set(
                            "run_id",
                            "skill_alias",
                            "coordinate",
                            "content_digest",
                            "reason",
                            "requested_by",
                            "instruction_bytes",
                            "estimated_tokens",
                            "activation_schema_version",
                            "activation_payload",
                            "activation_hash",
                            "activated_at")),
            entry("skill_resource_usage", set("run_id", "bytes_read", "updated_at")),
            entry(
                    "conversation_summary",
                    set(
                            "summary_id",
                            "summary_version",
                            "session_id",
                            "covered_from",
                            "covered_through",
                            "source_hash",
                            "content_schema_version",
                            "content_payload",
                            "content_hash",
                            "estimated_tokens",
                            "policy_version",
                            "compressor_version",
                            "valid",
                            "created_at")),
            entry(
                    "tool_result_asset",
                    set(
                            "asset_ref",
                            "tool_call_id",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "byte_length",
                            "created_at")),
            entry(
                    "tool_journal",
                    set(
                            "run_id",
                            "idempotency_key",
                            "state",
                            "tool_idempotency",
                            "result_schema_version",
                            "result_payload",
                            "result_hash",
                            "dispatch_execution_id",
                            "dispatch_process_id",
                            "dispatch_workdir_digest",
                            "reconcile_status",
                            "reconcile_reason",
                            "created_at",
                            "updated_at")),
            entry(
                    "interaction_request",
                    set(
                            "request_id",
                            "run_id",
                            "tenant_id",
                            "principal_id",
                            "principal_type",
                            "type",
                            "prompt",
                            "approval",
                            "target_type",
                            "target_schema_version",
                            "target_payload",
                            "target_hash",
                            "created_at",
                            "expires_at",
                            "revision",
                            "kind",
                            "state",
                            "expiration_outcome",
                            "state_reason_code",
                            "state_changed_at")),
            entry(
                    "interaction_response",
                    set(
                            "response_id",
                            "request_id",
                            "run_id",
                            "response_type",
                            "inputs_schema_version",
                            "inputs_payload",
                            "inputs_hash",
                            "idempotency_key",
                            "responded_at",
                            "resolved_at",
                            "action",
                            "expected_revision",
                            "caller_scope",
                            "canonical_digest",
                            "responder_tenant_id",
                            "responder_principal_id",
                            "responder_principal_type",
                            "receipt_status")),
            entry(
                    "run_input",
                    set(
                            "input_id",
                            "run_id",
                            "expected_run_version",
                            "contents_schema_version",
                            "contents_payload",
                            "contents_hash",
                            "caller_scope",
                            "idempotency_key",
                            "canonical_digest",
                            "submitted_at",
                            "accepted_at",
                            "status",
                            "applied_at",
                            "attempt_id",
                            "iteration",
                            "reason_code")),
            entry("interaction_application", set("request_id", "resolution_applied", "applied_at")));

    private static final Set<String> EXPECTED_INDEXES = set(
            "idx_session_tenant_owner",
            "idx_run_session_created",
            "idx_run_status_updated",
            "uq_active_attempt_per_run",
            "idx_attempt_run_number",
            "idx_message_run_sequence",
            "idx_tool_call_run_requested",
            "idx_event_run_occurred",
            "idx_outbox_pending",
            "idx_summary_latest_valid",
            "idx_tool_journal_uncertain",
            "idx_tool_journal_dispatch_execution",
            "idx_interaction_request_pending",
            "idx_runtime_event_run_sequence",
            "uq_interaction_pending_per_run",
            "idx_interaction_due",
            "uq_interaction_response_idempotency",
            "idx_run_input_pending");

    private static final Set<String> EXPECTED_FOREIGN_KEYS = set(
            "run.session_id->session.session_id",
            "run.configuration_ref->configuration_snapshot.configuration_ref",
            "run.root_run_id->run.run_id",
            "run.parent_run_id->run.run_id",
            "execution_attempt.run_id->run.run_id",
            "execution_attempt.resumed_from_checkpoint_id->checkpoint.checkpoint_id",
            "session_message.session_id->session.session_id",
            "session_message.run_id->run.run_id",
            "step.run_id->run.run_id",
            "tool_call.step_id->step.step_id",
            "checkpoint_payload.checkpoint_id->checkpoint.checkpoint_id",
            "runtime_event_stream.run_id->run.run_id",
            "outbox.event_id->runtime_event.event_id",
            "model_continuation.assistant_message_id->session_message.message_id",
            "interaction_response.request_id->interaction_request.request_id",
            "run_input.run_id->run.run_id",
            "run_input.attempt_id->execution_attempt.attempt_id",
            "interaction_application.request_id->interaction_request.request_id");
}
