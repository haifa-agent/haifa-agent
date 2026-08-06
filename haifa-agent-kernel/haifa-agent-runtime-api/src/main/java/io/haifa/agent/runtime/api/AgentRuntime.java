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

    default Optional<AgentRunViewSnapshot> view(io.haifa.agent.core.run.AgentRunId runId) {
        throw new UnsupportedOperationException("transport-ready Run views are not supported");
    }

    /** Returns the authoritative plan when this Runtime has one for the caller-visible Run. */
    default Optional<AgentPlanView> plan(io.haifa.agent.core.run.AgentRunId runId) {
        throw new UnsupportedOperationException("public plan views are not supported");
    }

    AgentRunHandle handle(io.haifa.agent.core.run.AgentRunId runId);

    void addListener(AgentRunListener listener);

    /**
     * Returns events from the bounded in-process output buffer after the supplied exclusive
     * sequence.
     *
     * <p>This is not a durable replay API. The buffer exists only while the Run is active in this
     * process.
     */
    List<AgentRunOutputEvent> outputEvents(io.haifa.agent.core.run.AgentRunId runId, RunOutputCursor after, int limit);

    /**
     * Replays the available in-process buffer and then tails transient output for one Run.
     *
     * <p>The returned subscription must be closed by the caller.
     */
    default RunOutputSubscription subscribeOutput(
            io.haifa.agent.core.run.AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        throw new UnsupportedOperationException("transient Run output subscriptions are not supported");
    }

    /**
     * The old global, non-removable listener is intentionally unsupported.
     *
     * @deprecated use {@link #subscribeOutput} with a Run-scoped, closeable subscription
     */
    @Deprecated(forRemoval = true)
    default void addOutputListener(AgentRunOutputListener listener) {
        throw new UnsupportedOperationException("use subscribeOutput with a Run-scoped subscription");
    }

    default RunEventPage events(io.haifa.agent.core.run.AgentRunId runId, RunEventCursor after, int limit) {
        throw new UnsupportedOperationException("complete Run Event Feed is implemented by Task 02");
    }

    default RunEventSubscription subscribe(
            io.haifa.agent.core.run.AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        throw new UnsupportedOperationException("Run Event Subscription is implemented by Task 02");
    }
}
