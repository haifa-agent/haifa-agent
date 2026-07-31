package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalDriverResultContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsTheSameShapeForUnixPtyAndConpty() throws Exception {
        JsonNode unix = result("unix-pty");
        JsonNode windows = result("conpty");

        assertTrue(TerminalDriverResultContract.validate(unix, "unix-pty", recording())
                .passed());
        assertTrue(TerminalDriverResultContract.validate(windows, "conpty", recording())
                .passed());
    }

    @Test
    void rejectsMissingProtocolAndBackendMismatch() throws Exception {
        JsonNode missingProtocol = result("unix-pty").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingProtocol).remove("driverProtocolVersion");

        var invalid = TerminalDriverResultContract.validate(missingProtocol, "unix-pty", recording());
        var mismatch = TerminalDriverResultContract.validate(result("conpty"), "unix-pty", recording());

        assertFalse(invalid.passed());
        assertTrue(invalid.violations().contains("INVALID_driverProtocolVersion"));
        assertTrue(invalid.violations().contains("UNSUPPORTED_DRIVER_PROTOCOL"));
        assertFalse(mismatch.passed());
        assertTrue(mismatch.violations().contains("TERMINAL_BACKEND_MISMATCH"));
    }

    @Test
    void rejectsRecordingDigestAndAsciicastEventCountMismatch() throws Exception {
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) result("conpty");
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("recording"))
                .put("sha256", "0".repeat(64))
                .put("events", 2);

        var validation = TerminalDriverResultContract.validate(evidence, "conpty", recording());

        assertFalse(validation.passed());
        assertTrue(validation.violations().contains("RECORDING_DIGEST_MISMATCH"));
        assertTrue(validation.violations().contains("ASCIICAST_EVENT_COUNT_MISMATCH"));
    }

    private JsonNode result(String backend) throws Exception {
        Path recording = recording();
        return json.readTree(
                """
                {
                  "schemaVersion": 2,
                  "driverProtocolVersion": "1.1.0",
                  "terminalBackend": "%s",
                  "terminalExitStatus": 0,
                  "agentWallTimeSeconds": 1.25,
                  "acceptanceExitStatus": 0,
                  "acceptanceStdout": "{}",
                  "acceptanceStderr": "",
                  "acceptancePassed": true,
                  "interactionCount": 1,
                  "humanFollowUps": 0,
                  "terminalStates": [
                    {"state": "IDLE", "atSeconds": 0.1},
                    {"state": "RUNNING", "atSeconds": 0.2},
                    {"state": "IDLE", "atSeconds": 0.3}
                  ],
                  "inputTimeline": [
                    {"action": "objective", "atSeconds": 0.15, "characters": 10},
                    {"action": "quit", "atSeconds": 0.4, "characters": 5}
                  ],
                  "recording": {
                    "format": "asciicast-v2",
                    "path": "session.cast",
                    "ansiMode": "preserved",
                    "sha256": "%s",
                    "bytes": %d,
                    "events": 1,
                    "truncated": false,
                    "columns": 132,
                    "rows": 42,
                    "encoding": "UTF-8"
                  }
                }
                """
                        .formatted(backend, Sha256Digests.file(recording), Files.size(recording)));
    }

    private Path recording() throws Exception {
        Path recording = temporaryDirectory.resolve("session.cast");
        Files.writeString(
                recording,
                """
                {"version":2,"width":132,"height":42,"timestamp":0,"env":{"TERM":"xterm-256color"}}
                [0.1,"o","IDLE\\r\\n"]
                """,
                StandardCharsets.UTF_8);
        return recording;
    }
}
