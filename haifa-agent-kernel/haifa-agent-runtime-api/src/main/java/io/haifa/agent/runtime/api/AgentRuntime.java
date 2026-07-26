package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Optional;

/** Stable entry point implemented by local or remote Agent runtimes. */
public interface AgentRuntime {

    AgentRunSnapshot start(AgentRunRequest request);

    AgentRunSnapshot resume(ResumeAgentRunRequest request);

    AgentRunSnapshot respond(InteractionResponse response);

    default InteractionResponseReceipt respond(InteractionResponseSubmission response) {
        throw new UnsupportedOperationException("revision-aware interaction responses are not supported");
    }

    default RunInputReceipt submitInput(RunInputSubmission input) {
        throw new UnsupportedOperationException("durable Run Input is not supported");
    }

    default Optional<InteractionView> pendingInteraction(io.haifa.agent.core.run.AgentRunId runId) {
        throw new UnsupportedOperationException("public Interaction views are not supported");
    }

    RuntimeCommandResult command(RuntimeCommand command);

    Optional<AgentRunSnapshot> find(io.haifa.agent.core.run.AgentRunId runId);

    AgentRunHandle handle(io.haifa.agent.core.run.AgentRunId runId);

    void addListener(AgentRunListener listener);

    /** Returns safe public output events after the supplied exclusive sequence. */
    List<AgentRunOutputEvent> outputEvents(io.haifa.agent.core.run.AgentRunId runId, RunOutputCursor after, int limit);

    void addOutputListener(AgentRunOutputListener listener);

    default RunEventPage events(io.haifa.agent.core.run.AgentRunId runId, RunEventCursor after, int limit) {
        throw new UnsupportedOperationException("complete Run Event Feed is implemented by Task 02");
    }

    default RunEventSubscription subscribe(
            io.haifa.agent.core.run.AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        throw new UnsupportedOperationException("Run Event Subscription is implemented by Task 02");
    }
}
