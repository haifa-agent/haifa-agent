package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
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
                .contains("--terminal")
                .contains("--trace <mode>")
                .contains("summary, detail, or jsonl")
                .contains("--trace-file <path>")
                .contains("required for Terminal trace");
    }

    @Test
    void explicitAndDefaultTerminalUseTheSameLaunchBoundary() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, output, trace) -> launches.incrementAndGet());

        assertThat(main.run(new String[] {"--terminal"}, output(), output())).isZero();
        assertThat(main.run(new String[0], output(), output())).isZero();
        assertThat(launches).hasValue(2);
    }

    @Test
    void defaultTerminalUsesTheProcessWorkingDirectoryAsItsWorkspace() {
        AtomicReference<Path> launchedWorkspace = new AtomicReference<>();
        var main = new HaifaCliMain((workspace, configuration, output, trace) -> launchedWorkspace.set(workspace));

        assertThat(main.run(new String[0], output(), output())).isZero();

        assertThat(launchedWorkspace).hasValue(Path.of(".").toAbsolutePath().normalize());
    }

    @Test
    void helpDoesNotInitializeTheTerminalApplication() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, output, trace) -> launches.incrementAndGet());
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();

        int exit = main.run(
                new String[] {"--help"}, new PrintStream(standardOutput, true, StandardCharsets.UTF_8), output());

        assertThat(exit).isZero();
        assertThat(launches).hasValue(0);
        assertThat(standardOutput.toString(StandardCharsets.UTF_8)).contains("Usage: haifa-cli");
    }

    @Test
    void terminalAndMessageConflictFailsBeforeLaunch() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, output, trace) -> launches.incrementAndGet());
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = main.run(
                new String[] {"--terminal", "-m", "task"},
                output(),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(1);
        assertThat(launches).hasValue(0);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("--terminal cannot be used with -m/--message");
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static AgentRunOutputEvent event(long sequence, AgentRunOutputEventType type, String text) {
        return new AgentRunOutputEvent(
                new AgentRunId("cli-run"), "call-1", "generation-1", 1, sequence, type, text, Instant.EPOCH);
    }
}
