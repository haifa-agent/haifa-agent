package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Produces a bounded projection of Run, execution authorization and Approval facts from the Stub Gate database. */
final class AutonomousDeliveryStubSqliteEvidenceReader {
    private final ObjectMapper json;

    AutonomousDeliveryStubSqliteEvidenceReader(ObjectMapper json) {
        this.json = json;
    }

    Evidence read(Path database) throws IOException {
        Path file = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("Stub Gate SQLite database is unavailable");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + file + "?mode=ro&immutable=1")) {
            boolean integrity = scalarText(connection, "PRAGMA integrity_check").equalsIgnoreCase("ok");
            boolean foreignKeys = count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") == 0;
            int runCount = count(connection, "SELECT COUNT(*) FROM run");
            int completedRuns = count(connection, "SELECT COUNT(*) FROM run WHERE status = 'COMPLETED'");
            int toolCalls = count(connection, "SELECT COUNT(*) FROM tool_call");
            int linkedToolCalls =
                    count(connection, "SELECT COUNT(*) FROM tool_call t JOIN run r ON r.run_id = t.run_id");
            int shellDecisions = count(
                    connection,
                    "SELECT COUNT(*) FROM policy_decision WHERE capability = 'execution.run' AND effect = 'ASK'");
            int approvedDecisions = count(
                    connection,
                    "SELECT COUNT(*) FROM policy_decision d JOIN policy_authorization_evidence e "
                            + "ON e.decision_id = d.decision_id WHERE d.capability = 'execution.run'");
            int deniedDecisions = count(
                    connection,
                    "SELECT COUNT(*) FROM policy_decision d LEFT JOIN policy_authorization_evidence e "
                            + "ON e.decision_id = d.decision_id WHERE d.capability = 'execution.run' "
                            + "AND d.effect = 'ASK' AND e.decision_id IS NULL");
            int orphanAuthorization = count(
                    connection,
                    "SELECT COUNT(*) FROM policy_authorization_evidence e LEFT JOIN policy_decision d "
                            + "ON d.decision_id = e.decision_id WHERE d.decision_id IS NULL");
            int linkedShellDecisions = count(
                    connection,
                    "SELECT COUNT(*) FROM policy_decision WHERE capability = 'execution.run' "
                            + "AND session_ref IS NOT NULL AND run_id IS NOT NULL");
            int shellMessages = shellMessageCount(connection);
            boolean passed = integrity
                    && foreignKeys
                    && runCount == 1
                    && completedRuns == 1
                    && toolCalls >= 1
                    && linkedToolCalls == toolCalls
                    && shellDecisions >= 5
                    && approvedDecisions >= 4
                    && deniedDecisions >= 1
                    && orphanAuthorization == 0
                    && linkedShellDecisions == shellDecisions
                    && shellMessages >= 4;
            return new Evidence(
                    1,
                    integrity,
                    foreignKeys,
                    runCount,
                    completedRuns,
                    toolCalls,
                    linkedToolCalls,
                    shellDecisions,
                    approvedDecisions,
                    deniedDecisions,
                    linkedShellDecisions,
                    shellMessages,
                    passed);
        } catch (SQLException exception) {
            throw new IOException("Stub Gate SQLite evidence could not be read", exception);
        }
    }

    private int shellMessageCount(Connection connection) throws SQLException, IOException {
        int count = 0;
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT metadata_payload FROM session_message WHERE role = 'RUNTIME'")) {
            while (rows.next()) {
                byte[] payload = rows.getBytes(1);
                if (payload == null) continue;
                JsonNode decoded = json.readTree(payload);
                JsonNode values = decoded.path("values");
                JsonNode metadata = values.isObject() ? values : decoded;
                if ("terminal-shell".equals(metadata.path("origin").asText())) count++;
            }
        }
        return count;
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : "";
        }
    }

    record Evidence(
            int schemaVersion,
            boolean integrityPassed,
            boolean foreignKeysPassed,
            int runCount,
            int completedRuns,
            int toolCallCount,
            int linkedToolCallCount,
            int shellDecisionCount,
            int approvedDecisionCount,
            int deniedDecisionCount,
            int linkedShellDecisionCount,
            int shellMessageCount,
            boolean passed) {
        Map<String, Object> artifact() {
            LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("schemaVersion", schemaVersion);
            artifact.put("integrityPassed", integrityPassed);
            artifact.put("foreignKeysPassed", foreignKeysPassed);
            artifact.put("runCount", runCount);
            artifact.put("completedRuns", completedRuns);
            artifact.put("toolCalls", toolCallCount);
            artifact.put("linkedToolCalls", linkedToolCallCount);
            artifact.put("executionAuthorizationDecisions", shellDecisionCount);
            artifact.put("approvedDecisions", approvedDecisionCount);
            artifact.put("deniedDecisions", deniedDecisionCount);
            artifact.put("linkedExecutionDecisions", linkedShellDecisionCount);
            artifact.put("persistedShellMessages", shellMessageCount);
            artifact.put("passed", passed);
            return Map.copyOf(artifact);
        }
    }
}
