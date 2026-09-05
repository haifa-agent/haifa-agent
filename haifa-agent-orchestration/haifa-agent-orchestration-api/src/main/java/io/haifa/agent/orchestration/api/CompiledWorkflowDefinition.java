package io.haifa.agent.orchestration.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CompiledWorkflowDefinition(
        WorkflowDefinitionRef reference,
        WorkflowDefinition definition,
        Set<WorkflowCapability> capabilities,
        Map<WorkflowDefinitionRef, WorkflowDefinition> subgraphDefinitions) {
    public CompiledWorkflowDefinition(
            WorkflowDefinitionRef reference, WorkflowDefinition definition, Set<WorkflowCapability> capabilities) {
        this(reference, definition, capabilities, Map.of());
    }

    public CompiledWorkflowDefinition {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        subgraphDefinitions =
                Map.copyOf(Objects.requireNonNull(subgraphDefinitions, "subgraphDefinitions must not be null"));
    }
}
