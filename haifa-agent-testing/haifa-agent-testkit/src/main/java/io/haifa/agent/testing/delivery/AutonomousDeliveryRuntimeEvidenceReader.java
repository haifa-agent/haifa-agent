package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads only bounded, safe evidence fields from the authoritative per-repeat SQLite store. */
final class AutonomousDeliveryRuntimeEvidenceReader {
    private static final List<String> SAFE_EVENT_FIELDS =
            List.of("iteration", "fingerprintDigest", "failureCategory", "attempts", "directive", "progressDigest");

    private final ObjectMapper json;

    AutonomousDeliveryRuntimeEvidenceReader(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    Evidence read(Path database) throws IOException {
        Path file = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("authoritative runtime database is unavailable");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + file + "?mode=ro&immutable=1")) {
            RunFacts run = readRun(connection);
            ToolFacts tools = readTools(connection);
            EventFacts events = readEvents(connection);
            return new Evidence(
                    run.status(),
                    run.inputTokens(),
                    run.outputTokens(),
                    run.modelCalls(),
                    run.toolCalls(),
                    run.costMinorUnits(),
                    tools.toolFailures(),
                    events.executionCalls(),
                    tools.validationAttempted(),
                    tools.diffInspected(),
                    events.scratchProvisionedCount(),
                    events.scratchCleanupFailures(),
                    events.maximumClusterAttempts(),
                    events.failureClusters(),
                    events.progress(),
                    events.terminalStateObserved() || terminal(run.status()));
        } catch (SQLException exception) {
            throw new IOException("authoritative runtime evidence could not be read", exception);
        }
    }

    private RunFacts readRun(Connection connection) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT status,
                               usage_input_tokens,
                               usage_output_tokens,
                               usage_model_calls,
                               usage_tool_calls,
                               usage_cost_minor_units
                        FROM run
                        """);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new IOException("runtime database contains no Run");
            RunFacts result = new RunFacts(
                    rows.getString(1),
                    rows.getLong(2),
                    rows.getLong(3),
                    rows.getLong(4),
                    rows.getLong(5),
                    rows.getLong(6));
            if (rows.next()) throw new IOException("per-repeat runtime database contains multiple Runs");
            return result;
        }
    }

    private ToolFacts readTools(Connection connection) throws SQLException, IOException {
        int failures = 0;
        boolean validationAttempted = false;
        boolean diffInspected = false;
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT tool_name, status, arguments_payload FROM tool_call");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String toolName = rows.getString(1);
                String status = rows.getString(2);
                if (!"COMPLETED".equals(status)) failures++;
                if (!"execution_run".equals(toolName)) continue;
                JsonNode arguments = decodeValues(rows.getBytes(3));
                String family = arguments.path("operationFamily").asText("UNKNOWN");
                validationAttempted |= family.equals("TEST") || family.equals("BUILD");
                diffInspected |= "COMPLETED".equals(status) && family.equals("DIFF");
            }
        }
        return new ToolFacts(failures, validationAttempted, diffInspected);
    }

    private EventFacts readEvents(Connection connection) throws SQLException, IOException {
        List<Map<String, Object>> clusters = new ArrayList<>();
        List<Map<String, Object>> progress = new ArrayList<>();
        int scratchProvisioned = 0;
        int scratchCleanupFailures = 0;
        int executionCalls = 0;
        int maximumAttempts = 0;
        boolean terminal = false;
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT type, data_payload FROM runtime_event ORDER BY sequence");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String type = rows.getString(1);
                JsonNode payload = decodeValues(rows.getBytes(2));
                switch (type) {
                    case "tool.failure-cluster-updated" -> {
                        int attempts = payload.path("attempts").asInt();
                        maximumAttempts = Math.max(maximumAttempts, attempts);
                        clusters.add(safeEvent(type, payload));
                    }
                    case "loop.progress-observed" -> progress.add(safeEvent(type, payload));
                    case "execution.scratch-provisioned" -> scratchProvisioned++;
                    case "execution.scratch-cleanup-failed" -> scratchCleanupFailures++;
                    case "execution.completed", "execution.failed" -> executionCalls++;
                    case "run.completed", "run.failed", "run.cancelled", "run.timed-out" -> terminal = true;
                    default -> {
                        // Other authoritative events are intentionally excluded from the safe Gate projection.
                    }
                }
            }
        }
        return new EventFacts(
                scratchProvisioned,
                scratchCleanupFailures,
                executionCalls,
                maximumAttempts,
                List.copyOf(clusters),
                List.copyOf(progress),
                terminal);
    }

    private JsonNode decodeValues(byte[] payload) throws IOException {
        if (payload == null) return json.createObjectNode();
        JsonNode decoded = json.readTree(payload);
        JsonNode values = decoded.path("values");
        return values.isObject() ? values : decoded;
    }

    private static Map<String, Object> safeEvent(String type, JsonNode payload) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", type);
        for (String key : SAFE_EVENT_FIELDS) {
            if (!payload.has(key)) continue;
            JsonNode value = payload.get(key);
            event.put(key, value.isNumber() ? value.numberValue() : value.asText());
        }
        return Map.copyOf(event);
    }

    private static boolean terminal(String status) {
        return switch (status) {
            case "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT" -> true;
            default -> false;
        };
    }

    record Evidence(
            String termination,
            long inputTokens,
            long outputTokens,
            long modelCalls,
            long toolCalls,
            long costMinorUnits,
            int toolFailures,
            int executionCalls,
            boolean validationAttempted,
            boolean diffInspected,
            int scratchProvisionedCount,
            int scratchCleanupFailures,
            int maximumClusterAttempts,
            List<Map<String, Object>> failureClusters,
            List<Map<String, Object>> progress,
            boolean terminalStateObserved) {
        boolean scratchSatisfied() {
            return scratchCleanupFailures == 0 && scratchProvisionedCount == executionCalls;
        }
    }

    private record RunFacts(
            String status, long inputTokens, long outputTokens, long modelCalls, long toolCalls, long costMinorUnits) {}

    private record ToolFacts(int toolFailures, boolean validationAttempted, boolean diffInspected) {}

    private record EventFacts(
            int scratchProvisionedCount,
            int scratchCleanupFailures,
            int executionCalls,
            int maximumClusterAttempts,
            List<Map<String, Object>> failureClusters,
            List<Map<String, Object>> progress,
            boolean terminalStateObserved) {}
}
