package io.haifa.agent.orchestration.api;

import java.util.Objects;

/** Frozen child definition and state boundary for a restricted static subgraph. */
public record WorkflowSubgraphBinding(
        WorkflowDefinitionRef definition,
        WorkflowStateMapping stateMapping,
        WorkflowSubgraphFailurePolicy failurePolicy) {
    public WorkflowSubgraphBinding(WorkflowDefinitionRef definition, WorkflowStateMapping stateMapping) {
        this(definition, stateMapping, WorkflowSubgraphFailurePolicy.FAIL_PARENT);
    }

    public WorkflowSubgraphBinding {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(stateMapping, "stateMapping must not be null");
        Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
    }
}
