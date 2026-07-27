package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalApplication;
import io.haifa.agent.application.coding.terminal.jline.JLineTerminalLifecycle;
import io.haifa.agent.application.coding.terminal.session.LocalCodingSessionClient;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Highest-layer production assembly for the single CLI/JLine Coding Agent process. */
final class LocalCodingTerminalRunner implements CliTerminalRunner {
    private final AgentFactory agentFactory;
    private final Supplier<JLineTerminalLifecycle> lifecycleFactory;

    LocalCodingTerminalRunner() {
        this(LocalCodingAgent::createWithTrace, JLineTerminalLifecycle::openSystem);
    }

    LocalCodingTerminalRunner(AgentFactory agentFactory, Supplier<JLineTerminalLifecycle> lifecycleFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.lifecycleFactory = Objects.requireNonNull(lifecycleFactory, "lifecycleFactory must not be null");
    }

    @Override
    public void run(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver) {
        try (JLineTerminalLifecycle lifecycle =
                        Objects.requireNonNull(lifecycleFactory.get(), "terminal lifecycle must not be null");
                LocalCodingAgent agent = agentFactory.create(workspace, configuration, output, traceObserver)) {
            var client = new LocalCodingSessionClient(
                    agent.projectId(),
                    agent.codingSessions(),
                    agent.runtime(),
                    agent.identifiers(),
                    agent.time(),
                    new LocalWorkspacePathCatalog(workspace)::list);
            new CodingTerminalApplication(agent.projectId(), client, Optional.empty(), () -> lifecycle).run();
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
