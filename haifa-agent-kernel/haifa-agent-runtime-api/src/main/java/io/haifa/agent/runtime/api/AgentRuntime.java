package io.haifa.agent.runtime.api;

import java.util.Optional;

/** Stable entry point implemented by local or remote Agent runtimes. */
public interface AgentRuntime {

    AgentRunResult start(AgentRunRequest request);

    AgentRunResult resume(ResumeAgentRunRequest request);

    RuntimeCommandResult execute(RuntimeCommand command);

    Optional<AgentRunResult> find(AgentRunQuery query);

    void addListener(AgentRunListener listener);
}
