package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.Optional;

public record WorkflowFailure(WorkflowErrorCode code, String operation, Optional<WorkflowNodeId> nodeId) {
    public WorkflowFailure {
        Objects.requireNonNull(code, "code must not be null");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }
}
