package io.haifa.agent.orchestration.api;

import java.util.Map;
import java.util.Objects;

public record WorkflowStateDelta(Map<String, Object> values) {
    private static final int ABSOLUTE_MAXIMUM_DEPTH = 32;
    private static final int ABSOLUTE_MAXIMUM_VALUES = 10_000;
    private static final int ABSOLUTE_MAXIMUM_STRING_LENGTH = 1_000_000;

    public WorkflowStateDelta {
        values = WorkflowStateValues.freeze(
                Objects.requireNonNull(values, "values must not be null"),
                ABSOLUTE_MAXIMUM_VALUES,
                ABSOLUTE_MAXIMUM_DEPTH,
                ABSOLUTE_MAXIMUM_STRING_LENGTH);
    }

    public static WorkflowStateDelta empty() {
        return new WorkflowStateDelta(Map.of());
    }
}
