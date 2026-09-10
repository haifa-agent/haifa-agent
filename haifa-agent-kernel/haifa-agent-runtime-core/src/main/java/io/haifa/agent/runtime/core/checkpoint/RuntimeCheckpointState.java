package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/** Minimal continuation counters for an intentional pause; facts remain in their authoritative stores. */
public record RuntimeCheckpointState(AgentRunId runId, int nextIteration, int forcedContextRebuildAttempts) {
    public RuntimeCheckpointState {
        Objects.requireNonNull(runId, "runId must not be null");
        if (nextIteration < 1) throw new IllegalArgumentException("nextIteration must be positive");
        if (forcedContextRebuildAttempts < 0 || forcedContextRebuildAttempts > 1) {
            throw new IllegalArgumentException("forced context rebuild attempts must be zero or one");
        }
    }
}
