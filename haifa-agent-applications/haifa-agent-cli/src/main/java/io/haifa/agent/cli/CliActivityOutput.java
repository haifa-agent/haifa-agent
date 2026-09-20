package io.haifa.agent.cli;

import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.RuntimeTraceScope;
import io.haifa.agent.runtime.core.trace.RuntimeTraceStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Renders content-free one-shot CLI activity without exposing private reasoning. */
final class CliActivityOutput implements AutoCloseable {
    static final Duration NON_TTY_INITIAL_DELAY = Duration.ofSeconds(30);
    static final Duration NON_TTY_INTERVAL = Duration.ofSeconds(60);
    static final Duration TTY_INTERVAL = Duration.ofSeconds(1);
    private static final int STATUS_CLEAR_WIDTH = 96;

    private final PrintStream output;
    private final PrintStream error;
    private final boolean statusEnabled;
    private final boolean tty;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> periodicStatus;
    private final AtomicBoolean streamed = new AtomicBoolean();
    private State state = State.IDLE;
    private long startedNanos;
    private boolean contentLineOpen;
    private boolean closed;
    private int runningTools;
    private String runningTool;

    static CliActivityOutput attach(
            Consumer<AgentRunOutputListener> registrar,
            PrintStream output,
            PrintStream error,
            boolean statusEnabled,
            boolean tty) {
        CliActivityOutput renderer = new CliActivityOutput(output, error, statusEnabled, tty, System::nanoTime, true);
        registrar.accept(renderer::onOutput);
        return renderer;
    }

    CliActivityOutput(
            PrintStream output,
            PrintStream error,
            boolean statusEnabled,
            boolean tty,
            LongSupplier nanoTime,
            boolean schedulePeriodicStatus) {
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.error = Objects.requireNonNull(error, "error must not be null");
        this.statusEnabled = statusEnabled;
        this.tty = tty;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        if (statusEnabled && schedulePeriodicStatus) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "haifa-cli-activity");
                thread.setDaemon(true);
                return thread;
            });
        } else {
            scheduler = null;
        }
    }

    AtomicBoolean streamed() {
        return streamed;
    }

    synchronized void onOutput(AgentRunOutputEvent event) {
        if (closed) return;
        Objects.requireNonNull(event, "event must not be null");
        switch (event.type()) {
            case RUN_OUTPUT_STARTED -> {
                finishContentLine();
                state = State.WAITING;
                startedNanos = nanoTime.getAsLong();
                if (statusEnabled && tty) renderTtyStatus();
                scheduleStatus();
            }
            case MODEL_ACTIVITY -> {
                if (state == State.WAITING || state == State.THINKING) {
                    state = State.THINKING;
                    if (statusEnabled && tty) renderTtyStatus();
                }
            }
            case ASSISTANT_TEXT_DELTA -> renderContent(event.textDelta());
            case ASSISTANT_TEXT_COMMITTED, RUN_OUTPUT_FAILED, RUN_OUTPUT_SUPERSEDED -> finishStatus();
        }
    }

    /**
     * Track tool execution so that the status line names what actually occupies the run.
     *
     * <p>A tool call can hold the run for minutes; reporting it as a wait for the model sends anyone
     * reading the log after the fact looking for a provider problem that is not there.
     */
    synchronized void onTrace(RuntimeTraceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (closed || !statusEnabled || event.scope() != RuntimeTraceScope.TOOL_CALL) return;
        if (event.status() == RuntimeTraceStatus.STARTED) {
            runningTools++;
            runningTool = toolName(event);
            enterStatus(State.TOOL);
        } else if (event.status() == RuntimeTraceStatus.SUCCESS || event.status() == RuntimeTraceStatus.FAILURE) {
            runningTools = Math.max(0, runningTools - 1);
            if (runningTools > 0 || state != State.TOOL) return;
            runningTool = null;
            enterStatus(State.WAITING);
        }
    }

    private void enterStatus(State next) {
        if (statusEnabled && tty && isActive()) clearTtyStatus();
        finishContentLine();
        state = next;
        startedNanos = nanoTime.getAsLong();
        if (statusEnabled && tty) renderTtyStatus();
        scheduleStatus();
    }

    private static String toolName(RuntimeTraceEvent event) {
        Object name = event.safeAttributes().get("toolName");
        return name == null ? null : name.toString();
    }

    private boolean isActive() {
        return state == State.WAITING || state == State.THINKING || state == State.TOOL;
    }

    synchronized void emitNonTtyStatus() {
        if (closed || !statusEnabled || tty || !isActive()) return;
        error.printf("[status] %s elapsed=%ds%n", statusText(), elapsedSeconds());
        error.flush();
    }

    synchronized void emitTtyStatus() {
        if (closed || !statusEnabled || !tty || !isActive()) return;
        renderTtyStatus();
    }

    private void renderContent(String delta) {
        if (delta.isEmpty()) return;
        if (statusEnabled && tty && isActive()) clearTtyStatus();
        state = State.CONTENT;
        if (streamed.compareAndSet(false, true)) output.print("[stream] ");
        output.print(delta);
        output.flush();
        contentLineOpen = !delta.endsWith("\n") && !delta.endsWith("\r");
    }

    private void renderTtyStatus() {
        error.printf("\r%s elapsed=%ds", statusText(), elapsedSeconds());
        error.flush();
    }

    private String statusText() {
        return switch (state) {
            case THINKING -> "Model is thinking...";
            case TOOL -> runningTool == null ? "Running a tool..." : "Running " + runningTool + "...";
            default -> "Waiting for model...";
        };
    }

    private long elapsedSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(Math.max(0, nanoTime.getAsLong() - startedNanos));
    }

    private void finishStatus() {
        if (statusEnabled && tty && isActive()) clearTtyStatus();
        state = State.IDLE;
        runningTools = 0;
        runningTool = null;
        if (periodicStatus != null) periodicStatus.cancel(false);
        periodicStatus = null;
    }

    private void scheduleStatus() {
        if (scheduler == null) return;
        if (periodicStatus != null) periodicStatus.cancel(false);
        Duration initialDelay = tty ? TTY_INTERVAL : NON_TTY_INITIAL_DELAY;
        Duration interval = tty ? TTY_INTERVAL : NON_TTY_INTERVAL;
        periodicStatus = scheduler.scheduleAtFixedRate(
                tty ? this::emitTtyStatus : this::emitNonTtyStatus,
                initialDelay.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void finishContentLine() {
        if (!contentLineOpen) return;
        output.println();
        output.flush();
        contentLineOpen = false;
    }

    private void clearTtyStatus() {
        error.print("\r" + " ".repeat(STATUS_CLEAR_WIDTH) + "\r");
        error.flush();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        finishStatus();
        finishContentLine();
        closed = true;
        if (scheduler != null) scheduler.shutdownNow();
    }

    private enum State {
        IDLE,
        WAITING,
        THINKING,
        TOOL,
        CONTENT
    }
}
