package io.haifa.agent.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
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
                        "call-1", "execution_run", "SUCCEEDED", "NONE", "workspace command", "result:1"),
                new RunEventPayloads.ResourceAvailable(
                        "checkpoint:1", "checkpoint", "Checkpoint 1", "AVAILABLE", "resume"));

        List<String> encoded = payloads.stream()
                .map(payload -> json.writeEvent(mapper.event(event(payload))))
                .toList();

        assertThat(encoded.get(0)).contains("\"toolCallId\":\"call-1\"").doesNotContain("apiKey", "reasoning");
        assertThat(encoded.get(0))
                .contains("\"occurredAt\":\"2026-07-27T00:00:00.123Z\"")
                .doesNotContain("456789");
        assertThat(encoded.get(1))
                .contains("\"reference\":\"checkpoint:1\"", "\"action\":\"resume\"")
                .doesNotContain("apiKey", "reasoning");
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
