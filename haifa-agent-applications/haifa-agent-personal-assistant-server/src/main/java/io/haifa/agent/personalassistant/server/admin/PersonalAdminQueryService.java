package io.haifa.agent.personalassistant.server.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read-only diagnostic projection over the Personal Assistant SQLite fact store.
 *
 * <p>This product adapter deliberately exposes complete persisted payloads to the separate loopback Admin API. It
 * never writes, migrates, logs, or copies those payloads into the ordinary Personal Assistant API.
 */
@Service
public final class PersonalAdminQueryService {
    private static final int MAXIMUM_TREE_ROWS_PER_KIND = 500;
    private static final Set<String> FAILURE_STATUSES =
            Set.of("FAILED", "TIMEOUT", "DENIED", "CANCELLED", "CORRUPTED", "ABANDONED");

    private final Path database;
    private final ObjectMapper mapper;

    public PersonalAdminQueryService(PersonalAssistantProperties properties, ObjectMapper mapper) {
        this.database = properties.dataDirectory().toAbsolutePath().normalize().resolve("personal-assistant.sqlite");
        this.mapper = Objects.requireNonNull(mapper);
    }

    public List<SessionSummary> sessions(int limit) {
        String sql =
                """
                SELECT s.session_id, s.status, s.created_at, s.updated_at,
                       COUNT(r.run_id) AS run_count,
                       (SELECT latest.status FROM run latest
                        WHERE latest.session_id = s.session_id
                        ORDER BY latest.created_at DESC, latest.run_id DESC LIMIT 1) AS latest_run_status
                FROM session s
                LEFT JOIN run r ON r.session_id = s.session_id
                GROUP BY s.session_id, s.status, s.created_at, s.updated_at
                ORDER BY s.updated_at DESC, s.session_id DESC
                LIMIT ?
                """;
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<SessionSummary> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(new SessionSummary(
                            rows.getString("session_id"),
                            rows.getString("status"),
                            instant(rows, "created_at").orElseThrow(),
                            instant(rows, "updated_at").orElseThrow(),
                            rows.getLong("run_count"),
                            Optional.ofNullable(rows.getString("latest_run_status"))));
                }
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw queryFailure(exception);
        }
    }

    public List<RunSummary> runs(String sessionId, int limit) {
        requireIdentifier(sessionId, "sessionId");
        String sql =
                """
                SELECT run_id, session_id, status, objective, created_at, updated_at, completed_at,
                       error_schema_version, error_payload, error_hash
                FROM run
                WHERE session_id = ?
                ORDER BY created_at DESC, run_id DESC
                LIMIT ?
                """;
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<RunSummary> values = new ArrayList<>();
                while (rows.next()) {
                    Object error = payload(rows, "error_schema_version", "error_payload", "error_hash");
                    values.add(new RunSummary(
                            rows.getString("run_id"),
                            rows.getString("session_id"),
                            rows.getString("status"),
                            rows.getString("objective"),
                            instant(rows, "created_at").orElseThrow(),
                            instant(rows, "updated_at").orElseThrow(),
                            instant(rows, "completed_at"),
                            errorCode(error)));
                }
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw queryFailure(exception);
        }
    }

    public Optional<Trace> trace(String sessionId, String runId) {
        requireIdentifier(sessionId, "sessionId");
        requireIdentifier(runId, "runId");
        try (Connection connection = open()) {
            RunRoot run = run(connection, sessionId, runId);
            if (run == null) return Optional.empty();

            List<Node> nodes = new ArrayList<>();
            Node root = run.root();
            String contextGroup = group(nodes, root.id(), runId, "context", "Prompt and messages", root.startedAt());
            String attemptsGroup = group(nodes, root.id(), runId, "attempts", "Execution attempts", root.startedAt());
            String loopGroup = group(nodes, root.id(), runId, "loop", "Agent Loop", root.startedAt());
            String interactionsGroup = group(nodes, root.id(), runId, "interactions", "Interactions", root.startedAt());
            String skillsGroup = group(nodes, root.id(), runId, "skills", "Skills", root.startedAt());

            configuration(connection, run.configurationRef(), contextGroup, nodes);
            messages(connection, runId, contextGroup, nodes);
            attempts(connection, runId, attemptsGroup, nodes);
            steps(connection, runId, loopGroup, nodes);
            tools(connection, runId, loopGroup, nodes);
            checkpoints(connection, runId, loopGroup, nodes);
            interactions(connection, runId, interactionsGroup, nodes);
            skills(connection, runId, skillsGroup, nodes);
            legacyStreamingOutput(connection, runId, loopGroup, nodes);
            events(connection, runId, root.id(), loopGroup, nodes);

            Optional<String> failureNode = nodes.stream()
                    .filter(node ->
                            node.status().map(PersonalAdminQueryService::failed).orElse(false))
                    .reduce((first, second) -> second)
                    .map(Node::id)
                    .or(() -> root.status()
                            .filter(PersonalAdminQueryService::failed)
                            .map(ignored -> root.id()));
            return Optional.of(new Trace(sessionId, runId, root, List.copyOf(nodes), failureNode));
        } catch (SQLException exception) {
            throw queryFailure(exception);
        }
    }

    private RunRoot run(Connection connection, String sessionId, String runId) throws SQLException {
        String sql =
                """
                SELECT run_id, session_id, root_run_id, parent_run_id, status, objective, run_type, invocation_mode,
                       depth, configuration_ref, termination_reason, created_at, started_at, completed_at, updated_at,
                       version, usage_input_tokens, usage_output_tokens, usage_cached_input_tokens,
                       usage_model_calls, usage_tool_calls, usage_child_runs, usage_wall_time_millis,
                       budget_max_input_tokens, budget_max_output_tokens, budget_max_tool_calls,
                       budget_max_model_calls, limit_max_iterations, limit_max_wall_time_millis,
                       result_schema_version, result_payload, result_hash,
                       error_schema_version, error_payload, error_hash
                FROM run WHERE session_id = ? AND run_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Object result = payload(row, "result_schema_version", "result_payload", "result_hash");
                Object error = payload(row, "error_schema_version", "error_payload", "error_hash");
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("objective", row.getString("objective"));
                details.put("runType", row.getString("run_type"));
                details.put("invocationMode", row.getString("invocation_mode"));
                details.put("rootRunId", row.getString("root_run_id"));
                put(details, "parentRunId", row.getString("parent_run_id"));
                details.put("depth", row.getLong("depth"));
                details.put("version", row.getLong("version"));
                put(details, "terminationReason", row.getString("termination_reason"));
                details.put(
                        "usage",
                        Map.of(
                                "inputTokens", row.getLong("usage_input_tokens"),
                                "outputTokens", row.getLong("usage_output_tokens"),
                                "cachedInputTokens", row.getLong("usage_cached_input_tokens"),
                                "modelCalls", row.getLong("usage_model_calls"),
                                "toolCalls", row.getLong("usage_tool_calls"),
                                "childRuns", row.getLong("usage_child_runs"),
                                "wallTimeMillis", row.getLong("usage_wall_time_millis")));
                details.put(
                        "budget",
                        Map.of(
                                "maximumInputTokens", row.getLong("budget_max_input_tokens"),
                                "maximumOutputTokens", row.getLong("budget_max_output_tokens"),
                                "maximumToolCalls", row.getLong("budget_max_tool_calls"),
                                "maximumModelCalls", row.getLong("budget_max_model_calls"),
                                "maximumIterations", row.getLong("limit_max_iterations"),
                                "maximumWallTimeMillis", row.getLong("limit_max_wall_time_millis")));
                put(details, "result", result);
                put(details, "error", error);
                Optional<Instant> startedAt = firstInstant(row, "started_at", "created_at");
                Optional<Instant> completedAt = instant(row, "completed_at");
                Node root = node(
                        "run:" + runId,
                        null,
                        "run",
                        "Run " + shortId(runId),
                        row.getString("status"),
                        startedAt.orElse(null),
                        completedAt.orElse(null),
                        null,
                        errorCode(error).orElse(row.getString("termination_reason")),
                        details);
                return new RunRoot(root, row.getString("configuration_ref"));
            }
        }
    }

    private void configuration(Connection connection, String configurationRef, String parentId, List<Node> nodes)
            throws SQLException {
        String sql =
                """
                SELECT configuration_ref, content_schema_version, content_payload, content_payload_hash, created_at
                FROM configuration_snapshot WHERE configuration_ref = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, configurationRef);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return;
                Object content = payload(row, "content_schema_version", "content_payload", "content_payload_hash");
                nodes.add(node(
                        "configuration:" + configurationRef,
                        parentId,
                        "configuration",
                        "Frozen agent and model configuration",
                        "FROZEN",
                        instant(row, "created_at").orElse(null),
                        null,
                        null,
                        "Includes the complete agent instruction and frozen tool/model bindings",
                        Map.of("configurationRef", configurationRef, "content", content)));
            }
        }
    }

    private void messages(Connection connection, String runId, String parentId, List<Node> nodes) throws SQLException {
        String sql =
                """
                SELECT message_id, parent_message_id, sequence, role, status, visibility,
                       content_schema_version, content_payload, content_hash,
                       metadata_schema_version, metadata_payload, metadata_hash, created_at
                FROM session_message WHERE run_id = ?
                ORDER BY sequence LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String messageId = rows.getString("message_id");
                    Object content = payload(rows, "content_schema_version", "content_payload", "content_hash");
                    Object metadata = payload(rows, "metadata_schema_version", "metadata_payload", "metadata_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("content", content);
                    details.put("metadata", metadata);
                    details.put("visibility", rows.getString("visibility"));
                    put(details, "parentMessageId", rows.getString("parent_message_id"));
                    nodes.add(node(
                            "message:" + messageId,
                            parentId,
                            "message",
                            rows.getString("role") + " message",
                            rows.getString("status"),
                            instant(rows, "created_at").orElse(null),
                            null,
                            rows.getLong("sequence"),
                            contentSummary(content),
                            details));
                }
            }
        }
    }

    private void attempts(Connection connection, String runId, String parentId, List<Node> nodes) throws SQLException {
        String sql =
                """
                SELECT attempt_id, attempt_number, status, created_at, started_at, heartbeat_at, completed_at,
                       worker_id, resumed_from_checkpoint_id, version,
                       error_schema_version, error_payload, error_hash
                FROM execution_attempt WHERE run_id = ?
                ORDER BY attempt_number LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String attemptId = rows.getString("attempt_id");
                    Object error = payload(rows, "error_schema_version", "error_payload", "error_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("attemptNumber", rows.getLong("attempt_number"));
                    details.put("version", rows.getLong("version"));
                    put(details, "workerId", rows.getString("worker_id"));
                    put(details, "resumedFromCheckpointId", rows.getString("resumed_from_checkpoint_id"));
                    instant(rows, "heartbeat_at").ifPresent(value -> details.put("heartbeatAt", value));
                    put(details, "error", error);
                    nodes.add(node(
                            "attempt:" + attemptId,
                            parentId,
                            "attempt",
                            "Attempt " + rows.getLong("attempt_number"),
                            rows.getString("status"),
                            firstInstant(rows, "started_at", "created_at").orElse(null),
                            instant(rows, "completed_at").orElse(null),
                            rows.getLong("attempt_number"),
                            errorCode(error).orElse(null),
                            details));
                }
            }
        }
    }

    private void steps(Connection connection, String runId, String parentId, List<Node> nodes) throws SQLException {
        String sql =
                """
                SELECT step_id, parent_step_id, branch_id, type, sequence, status, created_at, started_at, completed_at,
                       version, result_schema_version, result_payload, result_hash,
                       error_schema_version, error_payload, error_hash
                FROM step WHERE run_id = ? ORDER BY sequence LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String stepId = rows.getString("step_id");
                    String parentStepId = rows.getString("parent_step_id");
                    Object result = payload(rows, "result_schema_version", "result_payload", "result_hash");
                    Object error = payload(rows, "error_schema_version", "error_payload", "error_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("type", rows.getString("type"));
                    details.put("version", rows.getLong("version"));
                    put(details, "branchId", rows.getString("branch_id"));
                    put(details, "result", result);
                    put(details, "error", error);
                    nodes.add(node(
                            "step:" + stepId,
                            parentStepId == null ? parentId : "step:" + parentStepId,
                            "step",
                            "Step " + rows.getLong("sequence") + " · " + rows.getString("type"),
                            rows.getString("status"),
                            firstInstant(rows, "started_at", "created_at").orElse(null),
                            instant(rows, "completed_at").orElse(null),
                            rows.getLong("sequence"),
                            errorCode(error)
                                    .orElseGet(() -> resultSummary(result).orElse(null)),
                            details));
                }
            }
        }
    }

    private void tools(Connection connection, String runId, String parentId, List<Node> nodes) throws SQLException {
        String sql =
                """
                SELECT tool_call_id, step_id, provider_correlation_id, idempotency_key, tool_name, tool_version,
                       status, requested_at, started_at, completed_at, version,
                       arguments_schema_version, arguments_payload, arguments_hash,
                       result_schema_version, result_payload, result_hash,
                       error_schema_version, error_payload, error_hash
                FROM tool_call WHERE run_id = ? ORDER BY requested_at, tool_call_id LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String toolCallId = rows.getString("tool_call_id");
                    Object arguments = payload(rows, "arguments_schema_version", "arguments_payload", "arguments_hash");
                    Object result = payload(rows, "result_schema_version", "result_payload", "result_hash");
                    Object error = payload(rows, "error_schema_version", "error_payload", "error_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("toolName", rows.getString("tool_name"));
                    details.put("toolVersion", rows.getString("tool_version"));
                    details.put("version", rows.getLong("version"));
                    details.put("arguments", arguments);
                    put(details, "result", result);
                    put(details, "error", error);
                    put(details, "providerCorrelationId", rows.getString("provider_correlation_id"));
                    put(details, "idempotencyKey", rows.getString("idempotency_key"));
                    String stepId = rows.getString("step_id");
                    nodes.add(node(
                            "tool:" + toolCallId,
                            stepId == null ? parentId : "step:" + stepId,
                            toolKind(rows.getString("tool_name")),
                            rows.getString("tool_name"),
                            rows.getString("status"),
                            firstInstant(rows, "started_at", "requested_at").orElse(null),
                            instant(rows, "completed_at").orElse(null),
                            null,
                            errorCode(error)
                                    .orElseGet(() -> resultSummary(result).orElse(null)),
                            details));
                }
            }
        }
    }

    private void checkpoints(Connection connection, String runId, String parentId, List<Node> nodes)
            throws SQLException {
        String sql =
                """
                SELECT c.checkpoint_id, c.step_id, c.type, c.status, c.sequence, c.payload_store_type,
                       c.payload_location, c.payload_schema_id, c.payload_schema_version, c.state_hash, c.created_at,
                       p.state_schema_version, p.state_payload, p.payload_hash
                FROM checkpoint c
                LEFT JOIN checkpoint_payload p ON p.checkpoint_id = c.checkpoint_id
                WHERE c.run_id = ? ORDER BY c.sequence LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String checkpointId = rows.getString("checkpoint_id");
                    Object state = payload(rows, "state_schema_version", "state_payload", "payload_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("type", rows.getString("type"));
                    details.put("payloadStoreType", rows.getString("payload_store_type"));
                    details.put("payloadLocation", rows.getString("payload_location"));
                    details.put("payloadSchemaId", rows.getString("payload_schema_id"));
                    details.put("payloadSchemaVersion", rows.getString("payload_schema_version"));
                    details.put("stateHash", rows.getString("state_hash"));
                    put(details, "state", state);
                    String stepId = rows.getString("step_id");
                    nodes.add(node(
                            "checkpoint:" + checkpointId,
                            stepId == null ? parentId : "step:" + stepId,
                            "checkpoint",
                            "Checkpoint " + rows.getLong("sequence") + " · " + rows.getString("type"),
                            rows.getString("status"),
                            instant(rows, "created_at").orElse(null),
                            null,
                            rows.getLong("sequence"),
                            checkpointId,
                            details));
                }
            }
        }
    }

    private void interactions(Connection connection, String runId, String parentId, List<Node> nodes)
            throws SQLException {
        String sql =
                """
                SELECT request_id, type, kind, prompt, approval, state, revision, state_reason_code,
                       created_at, expires_at, state_changed_at,
                       target_schema_version, target_payload, target_hash
                FROM interaction_request WHERE run_id = ?
                ORDER BY created_at, request_id LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String requestId = rows.getString("request_id");
                    Object target = payload(rows, "target_schema_version", "target_payload", "target_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("prompt", rows.getString("prompt"));
                    details.put("type", rows.getString("type"));
                    details.put("kind", rows.getString("kind"));
                    details.put("approval", rows.getBoolean("approval"));
                    details.put("revision", rows.getLong("revision"));
                    details.put("expiresAt", instant(rows, "expires_at").orElse(null));
                    details.put("target", target);
                    put(details, "stateReasonCode", rows.getString("state_reason_code"));
                    nodes.add(node(
                            "interaction:" + requestId,
                            parentId,
                            rows.getBoolean("approval") ? "approval" : "interaction",
                            rows.getBoolean("approval") ? "Approval" : "Interaction",
                            rows.getString("state"),
                            instant(rows, "created_at").orElse(null),
                            instant(rows, "state_changed_at").orElse(null),
                            null,
                            rows.getString("prompt"),
                            details));
                }
            }
        }

        String responses =
                """
                SELECT response_id, request_id, response_type, action, receipt_status, expected_revision,
                       responded_at, resolved_at, inputs_schema_version, inputs_payload, inputs_hash
                FROM interaction_response WHERE run_id = ?
                ORDER BY responded_at, response_id LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(responses)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String responseId = rows.getString("response_id");
                    Object inputs = payload(rows, "inputs_schema_version", "inputs_payload", "inputs_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("responseType", rows.getString("response_type"));
                    details.put("action", rows.getString("action"));
                    details.put("expectedRevision", rows.getLong("expected_revision"));
                    details.put("inputs", inputs);
                    nodes.add(node(
                            "interaction-response:" + responseId,
                            "interaction:" + rows.getString("request_id"),
                            "interaction_response",
                            "Response · " + rows.getString("action"),
                            rows.getString("receipt_status"),
                            instant(rows, "responded_at").orElse(null),
                            instant(rows, "resolved_at").orElse(null),
                            null,
                            null,
                            details));
                }
            }
        }
    }

    private void skills(Connection connection, String runId, String parentId, List<Node> nodes) throws SQLException {
        String sql =
                """
                SELECT skill_alias, coordinate, content_digest, reason, requested_by, instruction_bytes,
                       estimated_tokens, activation_schema_version, activation_payload, activation_hash, activated_at
                FROM skill_activation WHERE run_id = ?
                ORDER BY activated_at, skill_alias LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String alias = rows.getString("skill_alias");
                    Object activation =
                            payload(rows, "activation_schema_version", "activation_payload", "activation_hash");
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("coordinate", rows.getString("coordinate"));
                    details.put("contentDigest", rows.getString("content_digest"));
                    details.put("reason", rows.getString("reason"));
                    details.put("requestedBy", rows.getString("requested_by"));
                    details.put("instructionBytes", rows.getLong("instruction_bytes"));
                    details.put("estimatedTokens", rows.getLong("estimated_tokens"));
                    details.put("activation", activation);
                    nodes.add(node(
                            "skill:" + alias,
                            parentId,
                            "skill",
                            "Skill · " + alias,
                            "ACTIVATED",
                            instant(rows, "activated_at").orElse(null),
                            null,
                            null,
                            rows.getString("reason"),
                            details));
                }
            }
        }
    }

    private void events(Connection connection, String runId, String runParentId, String loopParentId, List<Node> nodes)
            throws SQLException {
        String sql =
                """
                SELECT event_id, sequence, type, event_schema_version, data_schema_version,
                       data_payload, data_hash, occurred_at, correlation_id, causation_id
                FROM runtime_event
                WHERE run_id = ?
                  AND type NOT IN ('model.output.assistant_text_delta', 'model.output.delta')
                ORDER BY sequence LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setInt(2, MAXIMUM_TREE_ROWS_PER_KIND);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String eventId = rows.getString("event_id");
                    String type = rows.getString("type");
                    Object data = payload(rows, "data_schema_version", "data_payload", "data_hash");
                    Map<String, Object> values = eventValues(data);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("type", type);
                    details.put("eventSchemaVersion", rows.getString("event_schema_version"));
                    details.put("data", data);
                    put(details, "correlationId", rows.getString("correlation_id"));
                    put(details, "causationId", rows.getString("causation_id"));
                    nodes.add(node(
                            "event:" + eventId,
                            eventParent(type, values, runParentId, loopParentId),
                            "event",
                            type,
                            eventStatus(type, values),
                            instant(rows, "occurred_at").orElse(null),
                            null,
                            rows.getLong("sequence"),
                            eventSummary(values),
                            details));
                }
            }
        }
    }

    /**
     * Aggregates historical persisted deltas without allowing them to consume the diagnostic event
     * row limit. New runs never create these rows.
     */
    private void legacyStreamingOutput(Connection connection, String runId, String parentId, List<Node> nodes)
            throws SQLException {
        String sql =
                """
                SELECT sequence, data_schema_version, data_payload, data_hash, occurred_at
                FROM runtime_event
                WHERE run_id = ?
                  AND type IN ('model.output.assistant_text_delta', 'model.output.delta')
                ORDER BY sequence
                """;
        Map<String, LegacyStreamingAggregate> aggregates = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Object data = payload(rows, "data_schema_version", "data_payload", "data_hash");
                    Map<String, Object> values = eventValues(data);
                    if (!"ASSISTANT_TEXT_DELTA".equals(values.get("eventType"))) continue;
                    String generationId = String.valueOf(values.getOrDefault("generationId", "unknown"));
                    Object deltaValue = values.get("textDelta");
                    String delta = deltaValue instanceof String text ? text : "";
                    long sequence = rows.getLong("sequence");
                    Instant occurredAt = instant(rows, "occurred_at").orElse(null);
                    LegacyStreamingAggregate aggregate = aggregates.get(generationId);
                    if (aggregate == null) {
                        aggregate = new LegacyStreamingAggregate(generationId, sequence, occurredAt);
                        aggregates.put(generationId, aggregate);
                    }
                    aggregate.append(delta, occurredAt);
                }
            }
        }
        aggregates.values().forEach(aggregate -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("generationId", aggregate.generationId);
            details.put("deltaCount", aggregate.deltaCount);
            details.put("characterCount", aggregate.characterCount);
            details.put("aggregatedText", aggregate.text.toString());
            details.put("legacyPersistedData", true);
            nodes.add(node(
                    "legacy-streaming-output:" + aggregate.generationId,
                    parentId,
                    "legacy_streaming_output",
                    "Legacy Streaming Output",
                    "LEGACY",
                    aggregate.startedAt,
                    aggregate.completedAt,
                    aggregate.firstSequence,
                    aggregate.deltaCount + " deltas, " + aggregate.characterCount + " characters",
                    details));
        });
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        boolean success = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=1250");
            success = true;
            return connection;
        } finally {
            if (!success) connection.close();
        }
    }

    private Object payload(ResultSet row, String schemaColumn, String payloadColumn, String hashColumn)
            throws SQLException {
        byte[] bytes = row.getBytes(payloadColumn);
        if (bytes == null) return null;
        String schemaVersion = row.getString(schemaColumn);
        String expectedHash = row.getString(hashColumn);
        if (!hash(bytes).equals(expectedHash)) {
            throw new IllegalStateException("Admin diagnostic payload integrity check failed");
        }
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", schemaVersion);
            value.put("value", mapper.readValue(bytes, Object.class));
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException("Admin diagnostic payload could not be decoded", exception);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String group(
            List<Node> nodes, String parentId, String runId, String suffix, String label, Optional<Instant> startedAt) {
        String id = "group:" + runId + ":" + suffix;
        nodes.add(node(id, parentId, "group", label, null, startedAt.orElse(null), null, null, null, Map.of()));
        return id;
    }

    private static Node node(
            String id,
            String parentId,
            String kind,
            String label,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long sequence,
            String summary,
            Map<String, Object> details) {
        Long duration = startedAt == null || completedAt == null
                ? null
                : Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
        return new Node(
                id,
                Optional.ofNullable(parentId),
                kind,
                label,
                Optional.ofNullable(status),
                Optional.ofNullable(startedAt),
                Optional.ofNullable(completedAt),
                Optional.ofNullable(duration),
                Optional.ofNullable(sequence),
                Optional.ofNullable(summary).filter(value -> !value.isBlank()),
                Map.copyOf(details));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    private static Optional<Instant> firstInstant(ResultSet row, String preferred, String fallback)
            throws SQLException {
        Optional<Instant> value = instant(row, preferred);
        return value.isPresent() ? value : instant(row, fallback);
    }

    private static Optional<String> errorCode(Object payload) {
        Object value = payloadValue(payload);
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        Object code = map.get("code");
        return code instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private static Optional<String> resultSummary(Object payload) {
        Object value = payloadValue(payload);
        if (!(value instanceof Map<?, ?> map)) return Optional.empty();
        Object summary = map.get("summary");
        return summary instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
    }

    private static Object payloadValue(Object payload) {
        return payload instanceof Map<?, ?> wrapper ? wrapper.get("value") : null;
    }

    private static Map<String, Object> eventValues(Object payload) {
        Object decoded = payloadValue(payload);
        if (!(decoded instanceof Map<?, ?> wrapper)) return Map.of();
        Object values = wrapper.get("values");
        if (!(values instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String eventParent(
            String type, Map<String, Object> values, String runParentId, String loopParentId) {
        Object toolCallId = values.get("toolCallId");
        if (toolCallId instanceof String value && !value.isBlank()) return "tool:" + value;
        Object requestId = values.get("requestId");
        if (requestId instanceof String value && !value.isBlank()) return "interaction:" + value;
        Object stepId = values.get("stepId");
        if (stepId instanceof String value && !value.isBlank()) return "step:" + value;
        return type.startsWith("run.") || type.startsWith("runtime.command-") ? runParentId : loopParentId;
    }

    private static String eventStatus(String type, Map<String, Object> values) {
        Object status = values.get("status");
        if (status instanceof String value && !value.isBlank()) return value;
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.endsWith(".FAILED")) return "FAILED";
        if (normalized.endsWith(".SUCCEEDED") || normalized.endsWith(".COMPLETED")) return "COMPLETED";
        if (normalized.endsWith(".STARTED") || normalized.endsWith(".REQUESTED")) return "STARTED";
        if (normalized.endsWith(".CANCELLED")) return "CANCELLED";
        return null;
    }

    private static String eventSummary(Map<String, Object> values) {
        for (String key : List.of("reasonCode", "message", "commandSummary", "targetSummary", "resultRef")) {
            Object value = values.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        return null;
    }

    private static String contentSummary(Object payload) {
        Object value = payloadValue(payload);
        if (!(value instanceof Map<?, ?> wrapper)) return null;
        Object parts = wrapper.get("parts");
        if (!(parts instanceof List<?> list)) return null;
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(part -> part.get("text"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> !text.isBlank())
                .findFirst()
                .map(text -> text.length() > 160 ? text.substring(0, 160) + "…" : text)
                .orElse(null);
    }

    private static String toolKind(String toolName) {
        if (toolName == null) return "tool";
        if (toolName.startsWith("mcp.") || toolName.startsWith("mcp__")) return "mcp";
        if (toolName.startsWith("skill.")) return "skill_tool";
        if (toolName.contains("execution")) return "execution_tool";
        return "tool";
    }

    private static boolean failed(String status) {
        return FAILURE_STATUSES.contains(status.toUpperCase(Locale.ROOT));
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static String shortId(String value) {
        return value.substring(0, Math.min(12, value.length()));
    }

    private static IllegalStateException queryFailure(SQLException exception) {
        return new IllegalStateException("Admin diagnostic query failed", exception);
    }

    private record RunRoot(Node root, String configurationRef) {}

    private static final class LegacyStreamingAggregate {
        private final String generationId;
        private final long firstSequence;
        private final Instant startedAt;
        private final StringBuilder text = new StringBuilder();
        private Instant completedAt;
        private long deltaCount;
        private long characterCount;

        private LegacyStreamingAggregate(String generationId, long firstSequence, Instant startedAt) {
            this.generationId = generationId;
            this.firstSequence = firstSequence;
            this.startedAt = startedAt;
        }

        private void append(String delta, Instant occurredAt) {
            text.append(delta);
            deltaCount++;
            characterCount += delta.length();
            completedAt = occurredAt;
        }
    }

    public record SessionSummary(
            String id,
            String status,
            Instant createdAt,
            Instant updatedAt,
            long runCount,
            Optional<String> latestRunStatus) {}

    public record RunSummary(
            String id,
            String sessionId,
            String status,
            String objective,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> completedAt,
            Optional<String> errorCode) {}

    public record Trace(String sessionId, String runId, Node root, List<Node> nodes, Optional<String> failureNodeId) {}

    public record Node(
            String id,
            Optional<String> parentId,
            String kind,
            String label,
            Optional<String> status,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Optional<Long> durationMillis,
            Optional<Long> sequence,
            Optional<String> summary,
            Map<String, Object> details) {}
}
