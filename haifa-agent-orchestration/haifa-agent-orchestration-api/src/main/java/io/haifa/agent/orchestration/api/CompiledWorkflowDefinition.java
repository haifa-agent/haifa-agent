package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.Set;

public record CompiledWorkflowDefinition(
        WorkflowDefinitionRef reference, WorkflowDefinition definition, Set<WorkflowCapability> capabilities) {
    public CompiledWorkflowDefinition {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
    }
}
