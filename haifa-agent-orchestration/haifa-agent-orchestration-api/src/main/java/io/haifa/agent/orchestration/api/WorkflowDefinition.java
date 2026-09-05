package io.haifa.agent.orchestration.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record WorkflowDefinition(
        WorkflowDefinitionId id,
        WorkflowDefinitionVersion version,
        WorkflowStateSchema stateSchema,
        WorkflowNodeId entryNode,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdge> edges,
        WorkflowLimits limits,
        Set<WorkflowCapability> requiredCapabilities) {
    public WorkflowDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(stateSchema, "stateSchema must not be null");
        Objects.requireNonNull(entryNode, "entryNode must not be null");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges must not be null"));
        Objects.requireNonNull(limits, "limits must not be null");
        requiredCapabilities =
                Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null"));
    }
}
