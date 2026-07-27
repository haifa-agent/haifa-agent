package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.jline.JLineTerminalLifecycle;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Unique executable entry for interactive Terminal and compatible one-shot Coding Agent modes. */
public final class HaifaCliMain {
    private final CliTerminalRunner terminalRunner;

    HaifaCliMain() {
        this(new LocalCodingTerminalRunner());
    }

    HaifaCliMain(CliTerminalRunner terminalRunner) {
        this.terminalRunner = java.util.Objects.requireNonNull(terminalRunner, "terminalRunner must not be null");
    }

    public static void main(String[] arguments) {
        int exitCode = new HaifaCliMain().run(arguments, System.out, System.err);
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
            try (CliTraceOutput trace = CliTraceOutput.open(parsed.trace(), parsed.traceFile(), error)) {
                if (parsed.terminal() || parsed.message().isEmpty()) {
                    terminalRunner.run(workspace, configuration, output, trace);
                    return 0;
                }
                try (LocalCodingAgent agent =
                        LocalCodingAgent.createWithTrace(workspace, configuration, output, trace)) {
                    AtomicBoolean streamed = attachStreamingOutput(agent.runtime()::addOutputListener, output);
                    if (parsed.verbose()) output.println("Submitting coding task in " + workspace.getFileName());
                    if (parsed.verbose()) output.println("DeepSeek thinking disabled. Waiting for stream...");
                    var accepted = agent.start(parsed.message().orElseThrow());
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
                    completed
                            .error()
                            .ifPresent(value ->
                                    error.println("Task failed: " + value.code().value()));
                    if (!completed.status().isTerminal())
                        error.println("Task did not complete before the CLI timeout.");
                    return 2;
                }
            }
        } catch (IllegalArgumentException exception) {
            error.println("Invalid command: " + exception.getMessage());
            error.println("Use --help for usage.");
            return 1;
        } catch (IllegalStateException exception) {
            if (JLineTerminalLifecycle.TUI_UNAVAILABLE.equals(exception.getMessage())) {
                error.println(JLineTerminalLifecycle.TUI_UNAVAILABLE + ": an interactive terminal is required.");
                return 1;
            }
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        } catch (Exception exception) {
            error.println("Unable to run haifa-cli: " + exception.getClass().getSimpleName());
            return 1;
        }
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
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        while (System.currentTimeMillis() < deadlineMillis) {
            var snapshot = agent.runtime().find(runId).orElseThrow();
            if (snapshot.status().isTerminal()) return snapshot;
            if (agent.executionSettled(runId)) {
                agent.interactions()
                        .pending(runId)
                        .ifPresent(request -> respond(agent, request, approval, input, output));
            }
            Thread.sleep(50);
        }
        return agent.runtime().find(runId).orElseThrow();
    }

    private static void respond(
            LocalCodingAgent agent,
            io.haifa.agent.runtime.core.interaction.InteractionRequest request,
            ApprovalMode approval,
            BufferedReader input,
            PrintStream output) {
        InteractionResponseType response =
                switch (approval) {
                    case AUTO -> InteractionResponseType.APPROVE;
                    case DENY -> InteractionResponseType.REJECT;
                    case ASK ->
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
                Usage: haifa-cli [--terminal | -m <task>] [options]
                      --terminal             Start the interactive JLine Coding Terminal
                  -m, --message <task>       One-shot coding task
                      --workspace <path>     Workspace root (default: current directory)
                      --config <path>        Configuration file
                      --model <model-id>     Override configured model
                      --approval <mode>      ask, auto, or deny (default: ask)
                      --timeout <duration>   ISO-8601 duration, e.g. PT5M
                      --trace <mode>         summary, detail, or jsonl
                      --trace-file <path>    Write trace to a file (requires --trace)
                      --verbose              Print lifecycle details
                  -h, --help                 Show this help
                """;
    }
}
