package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowWaitId(String value) implements Identifier {
    public WorkflowWaitId {
        value = Identifiers.requireValid(value, "workflowWaitId");
    }
}
