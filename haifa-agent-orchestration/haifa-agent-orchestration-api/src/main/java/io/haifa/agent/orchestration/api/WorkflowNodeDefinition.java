package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.Optional;

public record WorkflowNodeDefinition(
        WorkflowNodeId id,
        WorkflowNodeType type,
        Optional<String> targetReference,
        Optional<WorkflowSubgraphBinding> subgraphBinding) {
    public WorkflowNodeDefinition(WorkflowNodeId id, WorkflowNodeType type, Optional<String> targetReference) {
        this(id, type, targetReference, Optional.empty());
    }

    public WorkflowNodeDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        targetReference = Objects.requireNonNull(targetReference, "targetReference must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        subgraphBinding = Objects.requireNonNull(subgraphBinding, "subgraphBinding must not be null");
        boolean executable = type == WorkflowNodeType.ACTION || type == WorkflowNodeType.AGENT_RUN;
        if (executable != targetReference.isPresent()) {
            throw new IllegalArgumentException(
                    "targetReference must be present exactly for ACTION and AGENT_RUN nodes");
        }
        if ((type == WorkflowNodeType.SUBGRAPH) != subgraphBinding.isPresent()) {
            throw new IllegalArgumentException("subgraphBinding must be present exactly for SUBGRAPH nodes");
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

    public static WorkflowNodeDefinition subgraph(String id, WorkflowSubgraphBinding binding) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id), WorkflowNodeType.SUBGRAPH, Optional.empty(), Optional.of(binding));
    }

    public static WorkflowNodeDefinition control(String id, WorkflowNodeType type) {
        return new WorkflowNodeDefinition(new WorkflowNodeId(id), type, Optional.empty());
    }
}
