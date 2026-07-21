package io.haifa.agent.runtime.api;

import java.util.Optional;

/** Stable entry point implemented by local or remote Agent runtimes. */
public interface AgentRuntime {

    AgentRunSnapshot start(AgentRunRequest request);

    AgentRunSnapshot resume(ResumeAgentRunRequest request);

    AgentRunSnapshot respond(InteractionResponse response);

    RuntimeCommandResult command(RuntimeCommand command);

    Optional<AgentRunSnapshot> find(io.haifa.agent.core.run.AgentRunId runId);

    AgentRunHandle handle(io.haifa.agent.core.run.AgentRunId runId);

    void addListener(AgentRunListener listener);
}
