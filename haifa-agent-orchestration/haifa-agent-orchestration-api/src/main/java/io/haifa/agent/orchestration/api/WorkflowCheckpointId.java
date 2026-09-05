package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowCheckpointId(String value) implements Identifier {
    public WorkflowCheckpointId {
        value = Identifiers.requireValid(value, "workflowCheckpointId");
    }
}
