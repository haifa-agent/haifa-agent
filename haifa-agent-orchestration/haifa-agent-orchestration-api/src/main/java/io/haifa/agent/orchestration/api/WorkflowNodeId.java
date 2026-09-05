package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record WorkflowNodeId(String value) implements Identifier, Comparable<WorkflowNodeId> {
    public WorkflowNodeId {
        value = Identifiers.requireValid(value, "workflowNodeId");
    }

    @Override
    public int compareTo(WorkflowNodeId other) {
        return value.compareTo(other.value);
    }
}
