package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.Optional;

public record WorkflowNodeDefinition(WorkflowNodeId id, WorkflowNodeType type, Optional<String> targetReference) {
    public WorkflowNodeDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        targetReference = Objects.requireNonNull(targetReference, "targetReference must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        boolean executable = type == WorkflowNodeType.ACTION || type == WorkflowNodeType.AGENT_RUN;
        if (executable != targetReference.isPresent()) {
            throw new IllegalArgumentException(
                    "targetReference must be present exactly for ACTION and AGENT_RUN nodes");
        }
    }

    public static WorkflowNodeDefinition action(String id, String targetReference) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id), WorkflowNodeType.ACTION, Optional.of(targetReference));
    }

    public static WorkflowNodeDefinition agentRun(String id, String targetReference) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id), WorkflowNodeType.AGENT_RUN, Optional.of(targetReference));
    }

    public static WorkflowNodeDefinition control(String id, WorkflowNodeType type) {
        return new WorkflowNodeDefinition(new WorkflowNodeId(id), type, Optional.empty());
    }
}
