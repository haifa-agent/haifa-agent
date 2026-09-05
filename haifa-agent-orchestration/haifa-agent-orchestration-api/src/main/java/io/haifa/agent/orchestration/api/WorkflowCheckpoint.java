package io.haifa.agent.orchestration.api;

import java.time.Instant;
import java.util.Objects;

public record WorkflowCheckpoint(
        WorkflowCheckpointId id,
        WorkflowRunId runId,
        long revision,
        WorkflowNodeId resumeNode,
        WorkflowState state,
        Instant createdAt) {
    public WorkflowCheckpoint {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(resumeNode, "resumeNode must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
