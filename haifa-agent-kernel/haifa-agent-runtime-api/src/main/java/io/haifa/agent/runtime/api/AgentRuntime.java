package io.haifa.agent.runtime.api;

import java.util.Optional;

/** Stable entry point implemented by local or remote Agent runtimes. */
public interface AgentRuntime {

    AgentRunSnapshot start(AgentRunRequest request);

    AgentRunSnapshot resume(ResumeAgentRunRequest request);

    RuntimeCommandResult execute(RuntimeCommand command);

    Optional<AgentRunSnapshot> find(AgentRunQuery query);

    void addListener(AgentRunListener listener);
}
