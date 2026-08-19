package io.haifa.agent.orchestration.api;

public record WorkflowDefinitionVersion(long value) {
    public WorkflowDefinitionVersion {
        if (value < 1) {
            throw new IllegalArgumentException("workflow definition version must be positive");
        }
    }
}
