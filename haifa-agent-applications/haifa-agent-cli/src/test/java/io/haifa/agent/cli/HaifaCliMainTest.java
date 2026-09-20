package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.RuntimeTraceScope;
import io.haifa.agent.runtime.core.trace.RuntimeTraceStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        assertThat(HaifaCliMain.usage()).contains("--quiet");
    }

    @Test
    void ttyActivityUsesStderrWithoutExposingReasoningAndClearsBeforeContent() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong();
        CliActivityOutput renderer = new CliActivityOutput(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                true,
                true,
                nanos::get,
                false);

        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        nanos.set(java.time.Duration.ofSeconds(12).toNanos());
        renderer.onOutput(event(2, AgentRunOutputEventType.MODEL_ACTIVITY, ""));
        renderer.onOutput(event(3, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "answer"));
        renderer.close();

        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("[stream] answer" + System.lineSeparator());
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("Waiting for model...")
                .contains("Model is thinking... elapsed=12s")
                .doesNotContain("answer");
    }

    @Test
    void ttyStatusStartsOnANewLineAfterAnUnterminatedStreamedAnswer() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong();
        CliActivityOutput renderer = new CliActivityOutput(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                true,
                true,
                nanos::get,
                false);

        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        renderer.onOutput(event(2, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "first answer"));
        renderer.onOutput(event(3, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        renderer.onOutput(event(4, AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "second answer"));
        renderer.close();

        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .isEqualTo("[stream] first answer" + System.lineSeparator() + "second answer" + System.lineSeparator());
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("Waiting for model...");
    }

    @Test
    void ttyActivityRefreshesElapsedTimeWithoutWaitingForAnotherRuntimeEvent() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong();
        CliActivityOutput renderer = new CliActivityOutput(
                output(), new PrintStream(stderr, true, StandardCharsets.UTF_8), true, true, nanos::get, false);
        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        nanos.set(java.time.Duration.ofSeconds(7).toNanos());

        renderer.emitTtyStatus();
        renderer.close();

        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("Waiting for model... elapsed=7s");
    }

    @Test
    void nonTtyActivityIsPeriodicAndQuietSuppressesIt() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong(java.time.Duration.ofSeconds(30).toNanos());
        CliActivityOutput renderer = new CliActivityOutput(
                output(), new PrintStream(stderr, true, StandardCharsets.UTF_8), true, false, nanos::get, false);
        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        renderer.emitNonTtyStatus();
        renderer.onOutput(event(2, AgentRunOutputEventType.MODEL_ACTIVITY, ""));
        nanos.set(java.time.Duration.ofSeconds(90).toNanos());
        renderer.emitNonTtyStatus();
        renderer.close();

        ByteArrayOutputStream quietError = new ByteArrayOutputStream();
        CliActivityOutput quiet = new CliActivityOutput(
                output(), new PrintStream(quietError, true, StandardCharsets.UTF_8), false, false, nanos::get, false);
        quiet.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        quiet.onOutput(event(2, AgentRunOutputEventType.MODEL_ACTIVITY, ""));
        quiet.emitNonTtyStatus();
        quiet.close();

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("[status] Waiting for model... elapsed=0s")
                .contains("[status] Model is thinking... elapsed=60s");
        assertThat(quietError.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void aRunningToolIsReportedAsTheToolAndNotAsAWaitForTheModel() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong();
        CliActivityOutput renderer = new CliActivityOutput(
                output(), new PrintStream(stderr, true, StandardCharsets.UTF_8), true, false, nanos::get, false);

        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        renderer.onTrace(toolTrace("execution_run", RuntimeTraceStatus.STARTED));
        nanos.set(java.time.Duration.ofSeconds(930).toNanos());
        renderer.emitNonTtyStatus();
        renderer.onTrace(toolTrace("execution_run", RuntimeTraceStatus.SUCCESS));
        renderer.emitNonTtyStatus();
        renderer.close();

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("[status] Running execution_run... elapsed=930s")
                .contains("[status] Waiting for model... elapsed=0s");
    }

    @Test
    void theStatusReturnsToTheModelOnlyAfterTheLastParallelToolEnds() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicLong nanos = new AtomicLong();
        CliActivityOutput renderer = new CliActivityOutput(
                output(), new PrintStream(stderr, true, StandardCharsets.UTF_8), true, false, nanos::get, false);

        renderer.onOutput(event(1, AgentRunOutputEventType.RUN_OUTPUT_STARTED, ""));
        renderer.onTrace(toolTrace("file_read", RuntimeTraceStatus.STARTED));
        renderer.onTrace(toolTrace("execution_run", RuntimeTraceStatus.STARTED));
        renderer.onTrace(toolTrace("file_read", RuntimeTraceStatus.SUCCESS));
        renderer.emitNonTtyStatus();
        renderer.close();

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("[status] Running execution_run...")
                .doesNotContain("[status] Waiting for model... elapsed=0s");
    }

    @Test
    void explicitAndDefaultTerminalUseTheSameLaunchBoundary() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, startup, output, trace) -> launches.incrementAndGet());

        assertThat(main.run(new String[] {"--terminal"}, output(), output())).isZero();
        assertThat(main.run(new String[0], output(), output())).isZero();
        assertThat(launches).hasValue(2);
    }

    @Test
    void defaultTerminalRequestsAnAsynchronousLastSessionLoad() {
        AtomicReference<CodingTerminalStartup> startup = new AtomicReference<>();
        var main = new HaifaCliMain((workspace, configuration, requested, output, trace) -> startup.set(requested));

        assertThat(main.run(new String[0], output(), output())).isZero();

        assertThat(startup.get().mode()).isEqualTo(CodingTerminalStartup.Mode.AUTO_LAST);
        assertThat(startup.get().sessionId()).isEmpty();
        assertThat(startup.get().prompt()).isEmpty();
    }

    @Test
    void defaultTerminalUsesTheProcessWorkingDirectoryAsItsWorkspace() {
        AtomicReference<Path> launchedWorkspace = new AtomicReference<>();
        var main = new HaifaCliMain(
                (workspace, configuration, startup, output, trace) -> launchedWorkspace.set(workspace));

        assertThat(main.run(new String[0], output(), output())).isZero();

        assertThat(launchedWorkspace).hasValue(Path.of(".").toAbsolutePath().normalize());
    }

    @Test
    void helpDoesNotInitializeTheTerminalApplication() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, startup, output, trace) -> launches.incrementAndGet());
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();

        int exit = main.run(
                new String[] {"--help"}, new PrintStream(standardOutput, true, StandardCharsets.UTF_8), output());

        assertThat(exit).isZero();
        assertThat(launches).hasValue(0);
        assertThat(standardOutput.toString(StandardCharsets.UTF_8)).contains("Usage: haifa-coding");
    }

    @Test
    void terminalAndMessageConflictFailsBeforeLaunch() {
        AtomicInteger launches = new AtomicInteger();
        var main = new HaifaCliMain((workspace, configuration, startup, output, trace) -> launches.incrementAndGet());
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = main.run(
                new String[] {"--terminal", "-m", "task"},
                output(),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(1);
        assertThat(launches).hasValue(0);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("--terminal cannot be used with -m/--message");
    }

    @Test
    void resumePassesTheTypedStartupIntentToTheTerminalBoundary() {
        AtomicReference<CodingTerminalStartup> startup = new AtomicReference<>();
        var main = new HaifaCliMain((workspace, configuration, requested, output, trace) -> startup.set(requested));

        int exit = main.run(new String[] {"resume", "session-1", "continue", "the", "work"}, output(), output());

        assertThat(exit).isZero();
        assertThat(startup.get().mode()).isEqualTo(CodingTerminalStartup.Mode.SESSION);
        assertThat(startup.get().sessionId()).contains(new io.haifa.agent.core.session.AgentSessionId("session-1"));
        assertThat(startup.get().prompt()).contains("continue the work");
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static AgentRunOutputEvent event(long sequence, AgentRunOutputEventType type, String text) {
        return new AgentRunOutputEvent(
                new AgentRunId("cli-run"), "call-1", "generation-1", 1, sequence, type, text, Instant.EPOCH);
    }

    private static RuntimeTraceEvent toolTrace(String toolName, RuntimeTraceStatus status) {
        return new RuntimeTraceEvent(
                "trace-1",
                new AgentRunId("cli-run"),
                Optional.empty(),
                new AgentSessionId("session-1"),
                Optional.of(new AgentStepId("step-1")),
                Optional.of(new ToolCallId("call-1")),
                Optional.empty(),
                OptionalInt.of(1),
                RuntimePhase.BEFORE_DECISION_EXECUTION,
                status == RuntimeTraceStatus.STARTED ? "tool.execute" : "tool.persisted",
                RuntimeTraceScope.TOOL_CALL,
                status,
                Map.of("toolName", toolName),
                Instant.EPOCH);
    }
}
