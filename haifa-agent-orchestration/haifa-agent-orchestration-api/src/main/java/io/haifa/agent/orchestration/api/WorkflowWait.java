package io.haifa.agent.orchestration.api;

import java.time.Instant;
import java.util.Objects;

public record WorkflowWait(WorkflowWaitId id, WorkflowNodeId nodeId, long revision, Instant createdAt) {
    public WorkflowWait {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
