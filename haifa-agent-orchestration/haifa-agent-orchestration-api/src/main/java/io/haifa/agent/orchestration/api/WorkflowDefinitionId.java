package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowDefinitionId(String value) implements Identifier {
    public WorkflowDefinitionId {
        value = Identifiers.requireValid(value, "workflowDefinitionId");
    }
}
