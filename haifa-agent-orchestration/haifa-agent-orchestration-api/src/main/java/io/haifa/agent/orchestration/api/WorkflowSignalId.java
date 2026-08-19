package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowSignalId(String value) implements Identifier {
    public WorkflowSignalId {
        value = Identifiers.requireValid(value, "workflowSignalId");
    }
}
