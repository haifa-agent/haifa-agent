package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.coding.host.internal.CliConfiguration;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Legacy CLI entry; assembly is owned by the product Host module. */
public final class StandaloneCodingAgents {
    private StandaloneCodingAgents() {}

    public static CodingAgentClientFactory factory() {
        return StandaloneCodingAgents::open;
    }

    public static StandaloneCodingAgent open(Path workspace) {
        return new StandaloneCodingAgent(io.haifa.coding.host.internal.StandaloneCodingAgents.open(workspace));
    }

    public static StandaloneCodingAgent open(Path workspace, Path config) {
        return new StandaloneCodingAgent(io.haifa.coding.host.internal.StandaloneCodingAgents.open(workspace, config));
    }

    public static StandaloneCodingAgent open(Path workspace, Path config, Map<String, String> environment) {
        return new StandaloneCodingAgent(
                io.haifa.coding.host.internal.StandaloneCodingAgents.open(workspace, config, environment));
    }

    static StandaloneCodingAgent open(
            Path workspace, CliConfiguration config, PrintStream out, Consumer<RuntimeTraceEvent> trace) {
        return new StandaloneCodingAgent(
                io.haifa.coding.host.internal.StandaloneCodingAgents.open(workspace, config, out, trace));
    }

    static StandaloneCodingAgent open(
            Path workspace,
            CliConfiguration config,
            PrintStream out,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> trace) {
        return new StandaloneCodingAgent(
                io.haifa.coding.host.internal.StandaloneCodingAgents.open(workspace, config, out, model, trace));
    }

    static StandaloneCodingAgent open(
            Path workspace,
            CliConfiguration config,
            PrintStream out,
            AgentChatModel model,
            Consumer<RuntimeTraceEvent> trace,
            ModelContinuationProtector protector) {
        return new StandaloneCodingAgent(io.haifa.coding.host.internal.StandaloneCodingAgents.open(
                workspace, config, out, model, trace, protector));
    }
}
