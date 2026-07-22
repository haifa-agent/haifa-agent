package io.haifa.agent.runtime.api;

import java.util.List;
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

    /** Returns safe public output events after the supplied exclusive sequence. */
    List<AgentRunOutputEvent> outputEvents(io.haifa.agent.core.run.AgentRunId runId, RunOutputCursor after, int limit);

    void addOutputListener(AgentRunOutputListener listener);
}
