package io.haifa.agent.orchestration.api;

import java.util.Objects;

/** Stable ownership of a child workflow run by one parent subgraph node attempt. */
public record WorkflowParentLink(WorkflowRunId runId, WorkflowNodeId nodeId, int nodeAttempt) {
    public WorkflowParentLink {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (nodeAttempt < 1) {
            throw new IllegalArgumentException("nodeAttempt must be positive");
        }
    }
}
