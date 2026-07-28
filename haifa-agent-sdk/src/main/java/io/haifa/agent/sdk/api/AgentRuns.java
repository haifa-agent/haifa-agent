package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
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

    public AgentRunSnapshot await(AgentRunId runId) throws InterruptedException {
        return handle(runId).awaitCompletion();
    }

    public Optional<AgentRunSnapshot> await(AgentRunId runId, Duration timeout) throws InterruptedException {
        return handle(runId).awaitCompletion(Objects.requireNonNull(timeout, "timeout must not be null"));
    }
}
