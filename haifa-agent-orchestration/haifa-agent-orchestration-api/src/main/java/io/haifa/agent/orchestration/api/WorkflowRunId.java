package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowRunId(String value) implements Identifier {
    public WorkflowRunId {
        value = Identifiers.requireValid(value, "workflowRunId");
    }
}
