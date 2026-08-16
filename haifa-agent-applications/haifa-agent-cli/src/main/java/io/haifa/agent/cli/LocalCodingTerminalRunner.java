package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalApplication;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.application.coding.terminal.state.TerminalWorkspaceContext;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Highest-layer production assembly for the single CLI/tui4j Coding Agent process. */
final class LocalCodingTerminalRunner implements CliTerminalRunner {
    private final AgentFactory agentFactory;
    private final Supplier<Tui4jTerminalIo> terminalIoFactory;

    LocalCodingTerminalRunner() {
        this(StandaloneCodingAgents::open, Tui4jTerminalIo::system);
    }

    LocalCodingTerminalRunner(AgentFactory agentFactory, Supplier<Tui4jTerminalIo> terminalIoFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.terminalIoFactory = Objects.requireNonNull(terminalIoFactory, "terminalIoFactory must not be null");
    }

    @Override
    public void run(
            Path workspace,
            CliConfiguration configuration,
            CodingTerminalStartup startup,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        Tui4jTerminalIo terminalIo = Objects.requireNonNull(terminalIoFactory.get(), "terminal IO must not be null");
        terminalIo.requireInteractive();
        // The tui4j event loop is the only terminal renderer. Execution output is projected
        // through safe product results instead of being written concurrently to the alt screen.
        try (PrintStream executionSink = new PrintStream(OutputStream.nullOutputStream());
                StandaloneCodingAgent agent =
                        agentFactory.create(workspace, configuration, executionSink, traceObserver)) {
            new CodingTerminalApplication(
                            agent.projectId(),
                            agent.client(),
                            startup,
                            terminalIo,
                            new TerminalWorkspaceContext(
                                    workspace.toAbsolutePath().normalize().toString(),
                                    LocalGitBranchResolver.resolve(workspace).orElse("")))
                    .run();
        }
    }

    @FunctionalInterface
    interface AgentFactory {
        StandaloneCodingAgent create(
                Path workspace,
                CliConfiguration configuration,
                PrintStream output,
                Consumer<RuntimeTraceEvent> traceObserver);
    }
}
