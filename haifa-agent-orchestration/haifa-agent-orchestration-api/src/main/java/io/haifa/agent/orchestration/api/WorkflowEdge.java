package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.Optional;

public record WorkflowEdge(
        WorkflowNodeId source, WorkflowNodeId target, Optional<String> conditionId, int branchOrdinal) {
    public WorkflowEdge {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        conditionId = Objects.requireNonNull(conditionId, "conditionId must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (branchOrdinal < 0) {
            throw new IllegalArgumentException("branchOrdinal must not be negative");
        }
    }

    public static WorkflowEdge unconditional(String source, String target) {
        return new WorkflowEdge(new WorkflowNodeId(source), new WorkflowNodeId(target), Optional.empty(), 0);
    }

    public static WorkflowEdge conditional(String source, String target, String conditionId) {
        return new WorkflowEdge(new WorkflowNodeId(source), new WorkflowNodeId(target), Optional.of(conditionId), 0);
    }

    public static WorkflowEdge branch(String source, String target, int branchOrdinal) {
        return new WorkflowEdge(
                new WorkflowNodeId(source), new WorkflowNodeId(target), Optional.empty(), branchOrdinal);
    }
}
