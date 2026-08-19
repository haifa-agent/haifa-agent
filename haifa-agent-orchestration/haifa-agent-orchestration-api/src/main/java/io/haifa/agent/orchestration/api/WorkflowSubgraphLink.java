package io.haifa.agent.orchestration.api;

import java.util.Objects;

/** Observable active child run at a parent subgraph boundary. */
public record WorkflowSubgraphLink(
        WorkflowRunId runId, WorkflowDefinitionRef definition, WorkflowNodeId parentNodeId, int parentNodeAttempt) {
    public WorkflowSubgraphLink {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(parentNodeId, "parentNodeId must not be null");
        if (parentNodeAttempt < 1) {
            throw new IllegalArgumentException("parentNodeAttempt must be positive");
        }
    }
}
