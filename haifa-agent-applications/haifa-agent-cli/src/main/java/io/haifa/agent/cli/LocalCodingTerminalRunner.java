package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalApplication;
import io.haifa.agent.application.coding.terminal.session.LocalCodingSessionClient;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Highest-layer production assembly for the single CLI/tui4j Coding Agent process. */
final class LocalCodingTerminalRunner implements CliTerminalRunner {
    private final AgentFactory agentFactory;
    private final Supplier<Tui4jTerminalIo> terminalIoFactory;

    LocalCodingTerminalRunner() {
        this(LocalCodingAgent::createWithTrace, Tui4jTerminalIo::system);
    }

    LocalCodingTerminalRunner(AgentFactory agentFactory, Supplier<Tui4jTerminalIo> terminalIoFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.terminalIoFactory = Objects.requireNonNull(terminalIoFactory, "terminalIoFactory must not be null");
    }

    @Override
    public void run(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        try (LocalCodingAgent agent = agentFactory.create(workspace, configuration, output, traceObserver)) {
            var client = new LocalCodingSessionClient(
                    agent.projectId(),
                    agent.codingSessions(),
                    agent.runtime(),
                    agent.identifiers(),
                    agent.time(),
                    new LocalWorkspacePathCatalog(workspace)::list,
                    agent::loadedResources,
                    agent::reloadResources,
                    agent.shell(),
                    agent.exporter());
            new CodingTerminalApplication(
                            agent.projectId(),
                            client,
                            Optional.empty(),
                            Objects.requireNonNull(terminalIoFactory.get(), "terminal IO must not be null"))
                    .run();
        }
    }

    @FunctionalInterface
    interface AgentFactory {
        LocalCodingAgent create(
                Path workspace,
                CliConfiguration configuration,
                PrintStream output,
                Consumer<RuntimeTraceEvent> traceObserver);
    }
}
