package io.haifa.agent.testing.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.testing.evidence.Sha256Digests;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared result contract for the POSIX pexpect and Windows Node PTY drivers. */
final class TerminalDriverResultContract {
    static final String PROTOCOL_VERSION = "1.2.0";
    private static final int SCHEMA_VERSION = 2;
    private static final String RECORDING_FORMAT = "asciicast-v2";
    private static final String RECORDING_FILE = "session.cast";
    private static final String RECORDING_ENCODING = "UTF-8";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BACKENDS = Set.of("unix-pty", "conpty");
    private static final Set<String> RUN_TERMINAL_STATES =
            Set.of("IDLE", "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");

    private TerminalDriverResultContract() {}

    static Validation validate(JsonNode evidence, String expectedBackend, Path recordingPath) {
        List<String> violations = new ArrayList<>();
        if (evidence == null || !evidence.isObject()) {
            violations.add("DRIVER_RESULT_MISSING");
            return new Validation(false, List.copyOf(violations));
        }
        requireInteger(evidence, "schemaVersion", violations);
        if (evidence.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            violations.add("UNSUPPORTED_SCHEMA_VERSION");
        }
        requireText(evidence, "driverProtocolVersion", violations);
        if (!PROTOCOL_VERSION.equals(evidence.path("driverProtocolVersion").asText())) {
            violations.add("UNSUPPORTED_DRIVER_PROTOCOL");
        }
        requireText(evidence, "terminalBackend", violations);
        String backend = evidence.path("terminalBackend").asText();
        if (!BACKENDS.contains(backend)) {
            violations.add("UNKNOWN_TERMINAL_BACKEND");
        } else if (!backend.equals(expectedBackend)) {
            violations.add("TERMINAL_BACKEND_MISMATCH");
        }
        requireInteger(evidence, "terminalExitStatus", violations);
        requireNonNegativeNumber(evidence, "agentWallTimeSeconds", violations);
        requireInteger(evidence, "acceptanceExitStatus", violations);
        requireText(evidence, "acceptanceStdout", violations);
        requireText(evidence, "acceptanceStderr", violations);
        requireBoolean(evidence, "acceptancePassed", violations);
        requireNonNegativeInteger(evidence, "interactionCount", violations);
        requireNonNegativeInteger(evidence, "humanFollowUps", violations);
        validateStateTimeline(evidence.path("terminalStates"), violations);
        validateInputTimeline(evidence.path("inputTimeline"), violations);
        validateRecording(evidence.path("recording"), recordingPath, violations);
        return new Validation(violations.isEmpty(), List.copyOf(violations));
    }

    private static void validateStateTimeline(JsonNode timeline, List<String> violations) {
        if (!timeline.isArray() || timeline.size() != 3) {
            violations.add("INVALID_terminalStates");
            return;
        }
        double previousTime = -1;
        for (int index = 0; index < 3; index++) {
            JsonNode observation = timeline.get(index);
            double time = observation.path("atSeconds").asDouble(-1);
            String state = observation.path("state").asText();
            boolean expectedState = index == 0
                    ? "IDLE".equals(state)
                    : index == 1 ? "RUNNING".equals(state) : RUN_TERMINAL_STATES.contains(state);
            if (!observation.isObject()
                    || !expectedState
                    || !observation.path("atSeconds").isNumber()
                    || time < 0
                    || time < previousTime) {
                violations.add("INVALID_terminalStates");
                return;
            }
            previousTime = time;
        }
    }

    private static void validateInputTimeline(JsonNode timeline, List<String> violations) {
        List<String> expected = List.of("objective", "quit");
        if (!timeline.isArray() || timeline.size() != expected.size()) {
            violations.add("INVALID_inputTimeline");
            return;
        }
        double previousTime = -1;
        for (int index = 0; index < expected.size(); index++) {
            JsonNode input = timeline.get(index);
            double time = input.path("atSeconds").asDouble(-1);
            if (!input.isObject()
                    || !expected.get(index).equals(input.path("action").asText())
                    || !input.path("atSeconds").isNumber()
                    || time < 0
                    || time < previousTime
                    || !input.path("characters").isIntegralNumber()
                    || input.path("characters").asLong() <= 0) {
                violations.add("INVALID_inputTimeline");
                return;
            }
            previousTime = time;
        }
    }

    private static void validateRecording(JsonNode recording, Path recordingPath, List<String> violations) {
        if (!recording.isObject()) {
            violations.add("INVALID_recording");
            return;
        }
        requireText(recording, "format", violations);
        requireText(recording, "path", violations);
        requireText(recording, "ansiMode", violations);
        requireText(recording, "sha256", violations);
        requireNonNegativeInteger(recording, "bytes", violations);
        requirePositiveInteger(recording, "events", violations);
        requireBoolean(recording, "truncated", violations);
        requirePositiveInteger(recording, "columns", violations);
        requirePositiveInteger(recording, "rows", violations);
        requireText(recording, "encoding", violations);
        if (!RECORDING_FORMAT.equals(recording.path("format").asText())) {
            violations.add("UNSUPPORTED_RECORDING_FORMAT");
        }
        if (!RECORDING_FILE.equals(recording.path("path").asText())) {
            violations.add("INVALID_RECORDING_PATH");
        }
        if (!"preserved".equals(recording.path("ansiMode").asText())) {
            violations.add("INVALID_ANSI_MODE");
        }
        if (!RECORDING_ENCODING.equals(recording.path("encoding").asText())) {
            violations.add("UNSUPPORTED_RECORDING_ENCODING");
        }
        if (!Files.isRegularFile(recordingPath)) {
            violations.add("RECORDING_MISSING");
            return;
        }
        try {
            if (Files.size(recordingPath) != recording.path("bytes").asLong(-1)) {
                violations.add("RECORDING_SIZE_MISMATCH");
            }
            if (!Sha256Digests.file(recordingPath)
                    .equals(recording.path("sha256").asText())) {
                violations.add("RECORDING_DIGEST_MISMATCH");
            }
            validateAsciicast(recording, recordingPath, violations);
        } catch (IOException | RuntimeException exception) {
            violations.add("RECORDING_UNREADABLE");
        }
    }

    private static void validateAsciicast(JsonNode recording, Path recordingPath, List<String> violations)
            throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(recordingPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                violations.add("ASCIICAST_HEADER_MISSING");
                return;
            }
            JsonNode header = JSON.readTree(headerLine);
            if (!header.isObject()
                    || header.path("version").asInt(-1) != 2
                    || header.path("width").asInt(-1)
                            != recording.path("columns").asInt(-1)
                    || header.path("height").asInt(-1) != recording.path("rows").asInt(-1)) {
                violations.add("ASCIICAST_HEADER_INVALID");
            }
            int eventCount = 0;
            double previousTime = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode event = JSON.readTree(line);
                if (!event.isArray()
                        || event.size() != 3
                        || !event.get(0).isNumber()
                        || event.get(0).asDouble() < previousTime
                        || !event.get(1).isTextual()
                        || !"o".equals(event.get(1).asText())
                        || !event.get(2).isTextual()) {
                    violations.add("ASCIICAST_EVENT_INVALID");
                    return;
                }
                previousTime = event.get(0).asDouble();
                eventCount++;
            }
            if (eventCount != recording.path("events").asInt(-1)) {
                violations.add("ASCIICAST_EVENT_COUNT_MISMATCH");
            }
        }
    }

    private static void requireText(JsonNode evidence, String field, List<String> violations) {
        if (!evidence.path(field).isTextual()) violations.add("INVALID_" + field);
    }

    private static void requireBoolean(JsonNode evidence, String field, List<String> violations) {
        if (!evidence.path(field).isBoolean()) violations.add("INVALID_" + field);
    }

    private static void requireInteger(JsonNode evidence, String field, List<String> violations) {
        if (!evidence.path(field).isIntegralNumber()) violations.add("INVALID_" + field);
    }

    private static void requireNonNegativeInteger(JsonNode evidence, String field, List<String> violations) {
        JsonNode value = evidence.path(field);
        if (!value.isIntegralNumber() || value.asLong() < 0) violations.add("INVALID_" + field);
    }

    private static void requirePositiveInteger(JsonNode evidence, String field, List<String> violations) {
        JsonNode value = evidence.path(field);
        if (!value.isIntegralNumber() || value.asLong() <= 0) violations.add("INVALID_" + field);
    }

    private static void requireNonNegativeNumber(JsonNode evidence, String field, List<String> violations) {
        JsonNode value = evidence.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) {
            violations.add("INVALID_" + field);
        }
    }

    record Validation(boolean passed, List<String> violations) {
        Map<String, Object> artifact() {
            LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("schemaVersion", SCHEMA_VERSION);
            artifact.put("driverProtocolVersion", PROTOCOL_VERSION);
            artifact.put("passed", passed);
            artifact.put("violations", violations);
            return Map.copyOf(artifact);
        }
    }
}
