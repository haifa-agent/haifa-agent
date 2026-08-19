package io.haifa.agent.orchestration.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record WorkflowEvent(
        WorkflowRunId runId,
        long sequence,
        WorkflowEventType type,
        Optional<WorkflowNodeId> nodeId,
        Map<String, String> attributes,
        Instant occurredAt) {
    public WorkflowEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(type, "type must not be null");
        nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
