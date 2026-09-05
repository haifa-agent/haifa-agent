package io.haifa.agent.orchestration.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record WorkflowState(WorkflowStateSchema schema, Map<String, Object> values) {
    public WorkflowState {
        Objects.requireNonNull(schema, "schema must not be null");
        schema.validateTopLevel(Objects.requireNonNull(values, "values must not be null"));
        values = WorkflowStateValues.freeze(
                values, schema.maximumValues(), schema.maximumDepth(), schema.maximumStringLength());
    }

    public WorkflowState apply(WorkflowStateDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        Map<String, Object> updated = new LinkedHashMap<>(values);
        updated.putAll(delta.values());
        return new WorkflowState(schema, updated);
    }
}
