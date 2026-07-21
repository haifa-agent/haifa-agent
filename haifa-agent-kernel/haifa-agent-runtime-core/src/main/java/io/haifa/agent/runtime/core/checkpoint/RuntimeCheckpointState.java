package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Runtime-owned continuation state referenced by a Core checkpoint. */
public record RuntimeCheckpointState(
        AgentRunId runId, int nextIteration, List<String> decisionFingerprints, Instant capturedAt) {
    public RuntimeCheckpointState {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (nextIteration < 1) throw new IllegalArgumentException("nextIteration must be positive");
        decisionFingerprints =
                List.copyOf(Objects.requireNonNull(decisionFingerprints, "decisionFingerprints must not be null"));
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt must not be null");
    }
}
