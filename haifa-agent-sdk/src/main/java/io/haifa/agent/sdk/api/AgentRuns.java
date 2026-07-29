package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Stable run query and control facade; Runtime construction remains hidden. */
public final class AgentRuns {
    private final io.haifa.agent.runtime.api.AgentRuntime runtime;

    AgentRuns(io.haifa.agent.runtime.api.AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    public Optional<AgentRunSnapshot> find(AgentRunId runId) {
        return runtime.find(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public Optional<AgentRunViewSnapshot> view(AgentRunId runId) {
        return runtime.view(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public AgentRunHandle handle(AgentRunId runId) {
        return runtime.handle(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        return runtime.pendingInteraction(Objects.requireNonNull(runId, "runId must not be null"));
    }

    public InteractionResponseReceipt respond(InteractionResponseSubmission response) {
        return runtime.respond(Objects.requireNonNull(response, "response must not be null"));
    }

    public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
        return runtime.events(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                limit);
    }

    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        return runtime.subscribe(
                Objects.requireNonNull(runId, "runId must not be null"),
                Objects.requireNonNull(after, "after must not be null"),
                Objects.requireNonNull(listener, "listener must not be null"));
    }

    public AgentRunSnapshot await(AgentRunId runId) throws InterruptedException {
        return handle(runId).awaitCompletion();
    }

    public Optional<AgentRunSnapshot> await(AgentRunId runId, Duration timeout) throws InterruptedException {
        return handle(runId).awaitCompletion(Objects.requireNonNull(timeout, "timeout must not be null"));
    }
}
