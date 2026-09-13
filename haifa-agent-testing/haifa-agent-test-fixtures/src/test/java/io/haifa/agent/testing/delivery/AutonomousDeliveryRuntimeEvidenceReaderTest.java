package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryRuntimeEvidenceReaderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsBoundedAuthoritativeEvidenceWithoutProjectingUnsafePayloadFields(@TempDir Path temporary)
            throws Exception {
        Path database = temporary.resolve("runtime.db");
        try (Connection connection = createDatabase(database)) {
            insertRun(connection, "COMPLETED", 1200, 80, 4, 4, 0);
            insertTool(
                    connection,
                    "execution_run",
                    "COMPLETED",
                    "TEST",
                    Map.of("status", "EXITED", "scratchProvisioned", true, "exitCode", 0));
            insertTool(
                    connection,
                    "execution_run",
                    "COMPLETED",
                    "DIFF",
                    Map.of("status", "EXITED", "scratchProvisioned", true, "exitCode", 0));
            insertTool(connection, "execution_run", "FAILED", "INSPECT", Map.of("status", "FAILED"));
            insertTool(connection, "execution_run", "DENIED", "UNKNOWN");
            insertEvent(
                    connection, 8, "run.completed", Map.of("status", "COMPLETED", "unsafePrompt", "do not project"));
        }

        var evidence = new AutonomousDeliveryRuntimeEvidenceReader(json).read(database);

        assertEquals("COMPLETED", evidence.termination());
        assertEquals(1200, evidence.inputTokens());
        assertEquals(80, evidence.outputTokens());
        assertEquals(4, evidence.modelCalls());
        assertEquals(4, evidence.toolCalls());
        assertEquals(2, evidence.toolFailures());
        assertEquals(2, evidence.executionCalls());
        assertTrue(evidence.validationAttempted());
        assertTrue(evidence.diffInspected());
        assertEquals(0, evidence.scratchCleanupFailures());
        assertTrue(evidence.terminalStateObserved());
        assertFalse(json.writeValueAsString(evidence).contains("do not project"));
    }

    @Test
    void doesNotCountFailedOrGenericInspectionAsDiffEvidence(@TempDir Path temporary) throws Exception {
        Path database = temporary.resolve("runtime.db");
        try (Connection connection = createDatabase(database)) {
            insertRun(connection, "FAILED", 10, 5, 2, 2, 0);
            insertTool(connection, "execution_run", "FAILED", "DIFF");
            insertTool(connection, "execution_run", "COMPLETED", "INSPECT");
        }

        var evidence = new AutonomousDeliveryRuntimeEvidenceReader(json).read(database);

        assertFalse(evidence.diffInspected());
    }

    @Test
    void scratchEvidenceFailsClosedOnCleanupFailure(@TempDir Path temporary) throws Exception {
        Path database = temporary.resolve("runtime.db");
        try (Connection connection = createDatabase(database)) {
            insertRun(connection, "FAILED", 10, 5, 1, 1, 0);
            insertTool(
                    connection,
                    "execution_run",
                    "FAILED",
                    "TEST",
                    Map.of("status", "FAILED", "exitCode", 1, "scratchCleanupFailed", true));
            insertEvent(connection, 3, "run.failed", Map.of("status", "FAILED"));
        }

        var evidence = new AutonomousDeliveryRuntimeEvidenceReader(json).read(database);

        assertEquals(1, evidence.scratchCleanupFailures());
    }

    @Test
    void scratchEvidenceRecordsZeroCleanupFailuresWhenClean(@TempDir Path temporary) throws Exception {
        Path database = temporary.resolve("runtime.db");
        try (Connection connection = createDatabase(database)) {
            insertRun(connection, "COMPLETED", 10, 5, 1, 1, 0);
            insertTool(
                    connection,
                    "execution_run",
                    "COMPLETED",
                    "TEST",
                    Map.of("status", "EXITED", "scratchProvisioned", false, "exitCode", 0));
        }

        var evidence = new AutonomousDeliveryRuntimeEvidenceReader(json).read(database);

        assertEquals(0, evidence.scratchCleanupFailures());
    }

    @Test
    void failedDriverWithoutRunProducesBoundedUnavailableEvidence(@TempDir Path temporary) throws Exception {
        Path database = temporary.resolve("runtime.db");
        try (Connection ignored = createDatabase(database)) {
            // A PTY startup failure can initialize SQLite without ever creating a Run.
        }
        AutonomousDeliveryRuntimeEvidenceReader reader = new AutonomousDeliveryRuntimeEvidenceReader(json);

        var unavailable = reader.readOrUnavailable(database, true);

        assertEquals("NOT_STARTED", unavailable.termination());
        assertFalse(unavailable.terminalStateObserved());
        assertEquals(0, unavailable.modelCalls());
        assertThrows(java.io.IOException.class, () -> reader.readOrUnavailable(database, false));
    }

    private static Connection createDatabase(Path database) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE run (
                        status TEXT NOT NULL,
                        usage_input_tokens INTEGER NOT NULL,
                        usage_output_tokens INTEGER NOT NULL,
                        usage_model_calls INTEGER NOT NULL,
                        usage_tool_calls INTEGER NOT NULL,
                        usage_cost_minor_units INTEGER NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE tool_call (
                        tool_name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        arguments_payload BLOB NOT NULL,
                        result_payload BLOB
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE runtime_event (
                        sequence INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        data_payload BLOB NOT NULL
                    )
                    """);
        }
        return connection;
    }

    private static void insertRun(
            Connection connection,
            String status,
            long inputTokens,
            long outputTokens,
            long modelCalls,
            long toolCalls,
            long costMinorUnits)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO run VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, status);
            statement.setLong(2, inputTokens);
            statement.setLong(3, outputTokens);
            statement.setLong(4, modelCalls);
            statement.setLong(5, toolCalls);
            statement.setLong(6, costMinorUnits);
            statement.executeUpdate();
        }
    }

    private void insertTool(Connection connection, String name, String status, String operationFamily)
            throws Exception {
        insertTool(connection, name, status, operationFamily, Map.of());
    }

    private void insertTool(
            Connection connection,
            String name,
            String status,
            String operationFamily,
            Map<String, Object> structuredData)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO tool_call VALUES (?, ?, ?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, status);
            statement.setBytes(
                    3,
                    json.writeValueAsBytes(Map.of(
                            "schemaId", "execution.input",
                            "schemaVersion", "1",
                            "values", Map.of("operationFamily", operationFamily))));
            statement.setBytes(
                    4,
                    structuredData.isEmpty() ? null : json.writeValueAsBytes(Map.of("structuredData", structuredData)));
            statement.executeUpdate();
        }
    }

    private void insertEvent(Connection connection, int sequence, String type, Map<String, Object> values)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO runtime_event VALUES (?, ?, ?)")) {
            statement.setInt(1, sequence);
            statement.setString(2, type);
            statement.setBytes(3, json.writeValueAsBytes(Map.of("values", values)));
            statement.executeUpdate();
        }
    }
}
