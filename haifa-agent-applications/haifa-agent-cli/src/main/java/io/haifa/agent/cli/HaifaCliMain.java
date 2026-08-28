package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jCodingTerminal;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Unique executable entry for interactive Terminal and compatible one-shot Coding Agent modes. */
public final class HaifaCliMain {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(HaifaCliMain.class.getName());
    private final CliTerminalRunner terminalRunner;

    HaifaCliMain() {
        this(new LocalCodingTerminalRunner());
    }

    HaifaCliMain(CliTerminalRunner terminalRunner) {
        this.terminalRunner = java.util.Objects.requireNonNull(terminalRunner, "terminalRunner must not be null");
    }

    public static void main(String[] arguments) {
        int exitCode;
        try (CliFileLogging logging = CliFileLogging.open(System.getenv())) {
            try {
                exitCode = new HaifaCliMain().run(arguments, System.out, System.err);
                logging.completed(exitCode);
            } catch (Error failure) {
                logging.logUncaught(Thread.currentThread(), failure);
                throw failure;
            }
        } catch (IOException | SecurityException exception) {
            System.err.println("Unable to initialize Coding Agent logs.");
            exitCode = 1;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    int run(String[] arguments, PrintStream output, PrintStream error) {
        try {
            CliArguments parsed = CliArguments.parse(arguments);
            if (parsed.help()) {
                output.println(usage());
                return 0;
            }
            Path workspace = parsed.workspace().orElseGet(() -> Path.of("."));
            if (!workspace.isAbsolute()) workspace = workspace.toAbsolutePath().normalize();
            CliConfiguration configuration = new CliConfigurationLoader().load(parsed, workspace);
            return run(parsed, workspace, configuration, output, error);
        } catch (IllegalArgumentException exception) {
            logRejected("CLI_ARGUMENT_REJECTED", exception);
            error.println("Invalid command: " + exception.getMessage());
            error.println("Use --help for usage.");
            return 1;
        } catch (IllegalStateException exception) {
            logRejected("CLI_STATE_REJECTED", exception);
            if (Tui4jCodingTerminal.TUI_UNAVAILABLE.equals(exception.getMessage())) {
                error.println(Tui4jCodingTerminal.TUI_UNAVAILABLE + ": an interactive terminal is required.");
                return 1;
            }
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        } catch (Exception exception) {
            logRejected("CLI_OPERATION_FAILED", exception);
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        }
    }

    int run(
            CliArguments parsed,
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            PrintStream error) {
        try {
            boolean terminal = parsed.resume().isPresent()
                    || parsed.terminal()
                    || parsed.message().isEmpty();
            LOGGER.log(java.util.logging.Level.INFO, "CLI_MODE terminal={0}", terminal);
            try (CliTraceOutput trace = terminal
                    ? CliTraceOutput.openForTerminal(parsed.trace(), parsed.traceFile())
                    : CliTraceOutput.open(parsed.trace(), parsed.traceFile(), error)) {
                if (terminal) {
                    CodingTerminalStartup startup =
                            parsed.resume().map(CliResumeRequest::startup).orElseGet(CodingTerminalStartup::empty);
                    terminalRunner.run(workspace, configuration, startup, output, trace);
                    return 0;
                }
                try (StandaloneCodingAgent standalone =
                        StandaloneCodingAgents.open(workspace, configuration, output, trace)) {
                    LocalCodingAgent agent = standalone.localAgent();
                    java.util.concurrent.atomic.AtomicReference<AgentRunOutputListener> outputListener =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    AtomicBoolean streamed = attachStreamingOutput(outputListener::set, output);
                    if (parsed.verbose()) output.println("Submitting coding task in " + workspace.getFileName());
                    if (parsed.verbose()) output.println("DeepSeek thinking disabled. Waiting for stream...");
                    var accepted = agent.start(parsed.message().orElseThrow());
                    var outputSubscription = agent.runtime()
                            .subscribeOutput(
                                    accepted.runId(),
                                    io.haifa.agent.runtime.api.RunOutputCursor.BEFORE_FIRST,
                                    outputListener.get());
                    if (parsed.verbose())
                        output.println("Run " + accepted.runId().value() + " submitted.");
                    Thread shutdownHook = Thread.ofPlatform()
                            .name("haifa-cli-cancel")
                            .unstarted(() -> cancelAndAwait(agent, accepted.runId(), Duration.ofSeconds(3)));
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                    io.haifa.agent.runtime.api.AgentRunSnapshot completed;
                    try {
                        completed = await(
                                agent, accepted.runId(), configuration.timeout(), configuration.approval(), output);
                    } finally {
                        outputSubscription.close();
                        removeShutdownHook(shutdownHook);
                    }
                    if (!completed.status().isTerminal()) {
                        agent.cancel(accepted.runId());
                        completed = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(3));
                    }
                    if (streamed.get()) output.println();
                    else completed.output().ifPresent(output::println);
                    if (parsed.verbose())
                        output.println("Reasoning tokens: " + agent.reasoningTokens(accepted.runId()));
                    if (completed.status().isTerminal()
                            && completed.status() == io.haifa.agent.core.run.AgentRunStatus.COMPLETED) {
                        return 0;
                    }
                    completed.error().ifPresent(value -> {
                        LOGGER.log(
                                java.util.logging.Level.WARNING,
                                "CLI_RUN_FAILED code={0} diagnosticId={1}",
                                new Object[] {
                                    value.code().wireCode(),
                                    value.optionalDiagnosticId()
                                            .map(Object::toString)
                                            .orElse("NONE")
                                });
                        error.println("[" + value.code().wireCode() + "] " + value.message());
                        value.optionalDiagnosticId()
                                .ifPresent(diagnosticId -> error.println("Diagnostic ID: " + diagnosticId));
                    });
                    if (!completed.status().isTerminal())
                        error.println("Task did not complete before the CLI timeout.");
                    return 2;
                }
            }
        } catch (IllegalArgumentException exception) {
            logRejected("CLI_ARGUMENT_REJECTED", exception);
            error.println("Invalid command: " + exception.getMessage());
            error.println("Use --help for usage.");
            return 1;
        } catch (IllegalStateException exception) {
            logRejected("CLI_STATE_REJECTED", exception);
            if (Tui4jCodingTerminal.TUI_UNAVAILABLE.equals(exception.getMessage())) {
                error.println(Tui4jCodingTerminal.TUI_UNAVAILABLE + ": an interactive terminal is required.");
                return 1;
            }
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        } catch (Exception exception) {
            logRejected("CLI_OPERATION_FAILED", exception);
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        }
    }

    private static void logRejected(String code, Exception exception) {
        LOGGER.log(java.util.logging.Level.WARNING, "{0} type={1}", new Object[] {
            code, exception.getClass().getName()
        });
    }

    static AtomicBoolean attachStreamingOutput(Consumer<AgentRunOutputListener> registrar, PrintStream output) {
        AtomicBoolean streamed = new AtomicBoolean();
        registrar.accept(event -> {
            if (event.type() != AgentRunOutputEventType.ASSISTANT_TEXT_DELTA
                    || event.textDelta().isEmpty()) return;
            if (streamed.compareAndSet(false, true)) output.print("[stream] ");
            output.print(event.textDelta());
            output.flush();
        });
        return streamed;
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot await(
            LocalCodingAgent agent,
            io.haifa.agent.core.run.AgentRunId runId,
            Duration timeout,
            ApprovalMode approval,
            PrintStream output)
            throws InterruptedException {
        long remainingNanos = timeout.toNanos();
        long lastObservedNanos = System.nanoTime();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        while (remainingNanos > 0) {
            var snapshot = agent.runtime().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) return snapshot;
            long observedNanos = System.nanoTime();
            if (!isHumanWait(snapshot.status())) {
                remainingNanos -= Math.max(0, observedNanos - lastObservedNanos);
                if (remainingNanos <= 0) break;
            }
            lastObservedNanos = observedNanos;
            if (agent.executionSettled(runId)) {
                var pending = agent.interactions().pending(runId);
                if (pending.isPresent()) {
                    respond(agent, pending.orElseThrow(), approval, input, output);
                    lastObservedNanos = System.nanoTime();
                    continue;
                }
            }
            Thread.sleep(50);
        }
        return agent.runtime().find(runId).orElseThrow();
    }

    private static boolean isHumanWait(AgentRunStatus status) {
        return status == AgentRunStatus.WAITING_INTERACTION || status == AgentRunStatus.WAITING_APPROVAL;
    }

    private static void respond(
            LocalCodingAgent agent,
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            ApprovalMode approval,
            BufferedReader input,
            PrintStream output) {
        InteractionResponseType response =
                switch (approval) {
                    case DENY -> InteractionResponseType.REJECT;
                    case ASK, AUTO ->
                        confirm(request.prompt(), input, output)
                                ? InteractionResponseType.APPROVE
                                : InteractionResponseType.REJECT;
                };
        agent.runtime()
                .respond(new InteractionResponse(
                        new InteractionResponseId(agent.identifiers().nextValue()),
                        request.id(),
                        request.runId(),
                        response,
                        List.of(),
                        "cli-interaction-" + request.id().value(),
                        agent.time().now()));
    }

    private static void cancelAndAwait(
            LocalCodingAgent agent, io.haifa.agent.core.run.AgentRunId runId, Duration timeout) {
        try {
            agent.cancel(runId);
            awaitTerminal(agent, runId, timeout);
        } catch (RuntimeException | InterruptedException ignored) {
            // JVM shutdown continues after a bounded best-effort cancellation.
        }
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot awaitTerminal(
            LocalCodingAgent agent, io.haifa.agent.core.run.AgentRunId runId, Duration timeout)
            throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        io.haifa.agent.runtime.api.AgentRunSnapshot snapshot =
                agent.runtime().find(runId).orElseThrow();
        while (!snapshot.status().isTerminal() && System.currentTimeMillis() < deadlineMillis) {
            Thread.sleep(25);
            snapshot = agent.runtime().find(runId).orElseThrow();
        }
        return snapshot;
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already started and owns the hook.
        }
    }

    private static boolean confirm(String prompt, BufferedReader input, PrintStream output) {
        output.print(prompt + " [y/N] ");
        output.flush();
        try {
            String answer = input.readLine();
            return answer != null && answer.trim().equalsIgnoreCase("y");
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    static String usage() {
        return """
                Usage: haifa-coding [--terminal | -m <task>] [options]
                       haifa-coding resume
                       haifa-coding resume --last [<prompt>]
                       haifa-coding resume <session-id> [<prompt>]
                Resume is limited to the current workspace and does not take over an active Run.
                      --terminal             Start the interactive tui4j Coding Terminal
                  -m, --message <task>       One-shot coding task
                      --workspace <path>     Workspace root (default: current directory)
                      --config <path>        Configuration file
                      --model <model-id>     Override configured model
                      --approval <mode>      ask=LOW, auto=NEVER, or deny (default: ask)
                      --timeout <duration>   ISO-8601 duration, e.g. PT5M
                      --trace <mode>         summary, detail, or jsonl
                      --trace-file <path>    Write trace to a file (required for Terminal trace)
                      --verbose              Print lifecycle details
                  -h, --help                 Show this help
                """;
    }
}
