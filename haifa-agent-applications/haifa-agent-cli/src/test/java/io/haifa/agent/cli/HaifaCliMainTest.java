package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HaifaCliMainTest {
    @Test
    void printsOnlyPublicAssistantDeltasAsTheyArrive() {
        AtomicReference<AgentRunOutputListener> listener = new AtomicReference<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var streamed =
                HaifaCliMain.attachStreamingOutput(listener::set, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        listener.get().onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        listener.get().onOutput(event(2, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "hello"));
        listener.get().onOutput(event(3, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, " world"));
        listener.get().onOutput(event(4, AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED, ""));

        assertThat(streamed).isTrue();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("[stream] hello world");
    }

    @Test
    void usageDocumentsSafeTraceOptions() {
        assertThat(HaifaCliMain.usage())
                .contains("--trace <mode>")
                .contains("summary, detail, or jsonl")
                .contains("--trace-file <path>")
                .contains("requires --trace");
    }

    private static AgentRunOutputEvent event(long sequence, AgentRunOutputEventType type, String text) {
        return new AgentRunOutputEvent(
                new AgentRunId("cli-run"), "call-1", "generation-1", 1, sequence, type, text, Instant.EPOCH);
    }
}
