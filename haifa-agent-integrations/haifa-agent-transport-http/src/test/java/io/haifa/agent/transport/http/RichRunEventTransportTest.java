package io.haifa.agent.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.display.BoundedText;
import io.haifa.agent.runtime.api.display.ToolDisplayBudget;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RichRunEventTransportTest {
    @Test
    void mapsAndSerializesTheRetainedPublicPayloads() {
        ContractRuntimeMapper mapper = new ContractRuntimeMapper(new PlainCursorCodec());
        HttpJsonCodec json = new HttpJsonCodec(new ObjectMapper().findAndRegisterModules());
        List<AgentRunEvent.Payload> payloads = List.of(
                new RunEventPayloads.ToolLifecycle(
                        "call-1",
                        "execution_run",
                        "SUCCEEDED",
                        "NONE",
                        "workspace command",
                        "result:1",
                        Optional.of(new RunEventPayloads.ToolObservation(
                                Optional.of(BoundedText.of("Command exited (exit 0)", new ToolDisplayBudget(128, 4))),
                                Optional.of("EXITED"),
                                Optional.of(0)))),
                new RunEventPayloads.ResourceAvailable(
                        "checkpoint:1", "checkpoint", "Checkpoint 1", "AVAILABLE", "resume"));

        List<String> encoded = payloads.stream()
                .map(payload -> json.writeEvent(mapper.event(event(payload))))
                .toList();

        assertThat(encoded.get(0)).contains("\"toolCallId\":\"call-1\"").doesNotContain("apiKey", "reasoning");
        assertThat(encoded.get(0))
                .contains("\"occurredAt\":\"2026-07-27T00:00:00.123Z\"")
                .doesNotContain("456789");
        assertThat(encoded.get(0))
                .contains(
                        "\"outputPreview\":\"Command exited (exit 0)\"",
                        "\"processState\":\"EXITED\"",
                        "\"exitCode\":0");
        assertThat(encoded.get(1))
                .contains("\"reference\":\"checkpoint:1\"", "\"action\":\"resume\"")
                .doesNotContain("apiKey", "reasoning");
    }

    @Test
    void mapsTruncatedFailedCancelledAndUnknownToolObservationsWithStableIdentity() {
        ContractRuntimeMapper mapper = new ContractRuntimeMapper(new PlainCursorCodec());
        HttpJsonCodec json = new HttpJsonCodec(new ObjectMapper().findAndRegisterModules());
        String longOutput = "line\n".repeat(50);

        RunEventPayloads.ToolLifecycle failed = new RunEventPayloads.ToolLifecycle(
                "call-1",
                "execution_run",
                "FAILED",
                "IO_FAILED",
                "cat log",
                "asset-1",
                Optional.of(new RunEventPayloads.ToolObservation(
                        Optional.of(BoundedText.of(longOutput, new ToolDisplayBudget(10_000, 4))),
                        Optional.of("EXITED"),
                        Optional.of(2))));
        RunEventPayloads.ToolLifecycle cancelled = new RunEventPayloads.ToolLifecycle(
                "call-1", "execution_run", "CANCELLED", "WALL_TIME_EXCEEDED", "cat log", "", Optional.empty());
        RunEventPayloads.ToolLifecycle unknown = new RunEventPayloads.ToolLifecycle(
                "call-1", "execution_run", "OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN", "rm -rf", "", Optional.empty());

        String failedJson = json.writeEvent(mapper.event(event(failed)));
        String cancelledJson = json.writeEvent(mapper.event(event(cancelled)));
        String unknownJson = json.writeEvent(mapper.event(event(unknown)));

        assertThat(failedJson)
                .contains(
                        "\"toolCallId\":\"call-1\"",
                        "\"status\":\"FAILED\"",
                        "\"reasonCode\":\"IO_FAILED\"",
                        "\"truncated\":true",
                        "\"truncationReason\":\"OUTPUT_LINES\"",
                        "\"processState\":\"EXITED\"",
                        "\"exitCode\":2");
        assertThat(cancelledJson).contains("\"status\":\"CANCELLED\"", "\"reasonCode\":\"WALL_TIME_EXCEEDED\"");
        assertThat(unknownJson)
                .contains(
                        "\"toolCallId\":\"call-1\"",
                        "\"status\":\"OUTCOME_UNKNOWN\"",
                        "\"reasonCode\":\"TOOL_OUTCOME_UNKNOWN\"")
                .doesNotContain("\"observation\":{");
        // Reconnect re-mapping of the same authoritative event stays byte-for-byte stable.
        assertThat(json.writeEvent(mapper.event(event(failed)))).isEqualTo(failedJson);
    }

    private static AgentRunEvent event(AgentRunEvent.Payload payload) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                "event-1",
                "tool.call.succeeded",
                "1",
                runId,
                new AgentSessionId("session-1"),
                1,
                new RunEventCursor(runId, "1", OptionalLong.of(1)),
                Instant.parse("2026-07-27T00:00:00.123456789Z"),
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    private static final class PlainCursorCodec implements RunEventCursorTokenCodec {
        @Override
        public String encode(RunEventCursor cursor) {
            return cursor.runId().value() + ":" + cursor.exclusiveSequence().orElse(0);
        }

        @Override
        public RunEventCursor decode(AgentRunId expectedRunId, String token) {
            return RunEventCursor.beforeFirst(expectedRunId);
        }
    }
}
