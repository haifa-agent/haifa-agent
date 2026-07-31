package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryStubSqliteEvidenceReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresCompletedRunAndLinkedApprovedAndDeniedShellDecisions() throws Exception {
        Path database = temporaryDirectory.resolve("stub.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE run(run_id TEXT PRIMARY KEY, status TEXT NOT NULL)");
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE tool_call(tool_call_id TEXT PRIMARY KEY, run_id TEXT REFERENCES run(run_id))");
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE policy_decision(decision_id TEXT PRIMARY KEY, capability TEXT, effect TEXT, session_ref TEXT, run_id TEXT)");
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE policy_authorization_evidence(decision_id TEXT PRIMARY KEY REFERENCES policy_decision(decision_id))");
            connection.createStatement().execute("CREATE TABLE session_message(role TEXT, metadata_payload BLOB)");
            connection.createStatement().execute("INSERT INTO run VALUES ('run-1', 'COMPLETED')");
            connection.createStatement().execute("INSERT INTO tool_call VALUES ('tool-1', 'run-1')");
            for (int index = 1; index <= 5; index++) {
                connection
                        .createStatement()
                        .execute("INSERT INTO policy_decision VALUES ('decision-" + index
                                + "', 'execution.run', 'ASK', 'session-1', 'terminal-" + index + "')");
                if (index <= 4) {
                    connection
                            .createStatement()
                            .execute("INSERT INTO policy_authorization_evidence VALUES ('decision-" + index + "')");
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO session_message VALUES ('RUNTIME', ?)")) {
                statement.setBytes(1, "{\"values\":{\"origin\":\"terminal-shell\"}}".getBytes());
                statement.executeUpdate();
                statement.executeUpdate();
                statement.executeUpdate();
                statement.executeUpdate();
            }
        }

        var reader = new AutonomousDeliveryStubSqliteEvidenceReader(new ObjectMapper());
        var evidence = reader.read(database);

        assertTrue(evidence.passed());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection
                    .createStatement()
                    .execute("DELETE FROM policy_authorization_evidence WHERE decision_id = 'decision-4'");
        }
        assertFalse(reader.read(database).passed());
    }
}
