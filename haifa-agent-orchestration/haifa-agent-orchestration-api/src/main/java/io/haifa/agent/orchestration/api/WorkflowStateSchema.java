package io.haifa.agent.orchestration.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record WorkflowStateSchema(
        String schemaId,
        long version,
        Set<String> allowedKeys,
        int maximumValues,
        int maximumDepth,
        int maximumStringLength) {
    public WorkflowStateSchema {
        schemaId = Objects.requireNonNull(schemaId, "schemaId must not be null").trim();
        if (schemaId.isEmpty() || version < 1) {
            throw new IllegalArgumentException("schema id must not be blank and version must be positive");
        }
        allowedKeys = Set.copyOf(Objects.requireNonNull(allowedKeys, "allowedKeys must not be null"));
        if (allowedKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("allowedKeys must not contain blank values");
        }
        if (maximumValues < 1 || maximumDepth < 1 || maximumStringLength < 1) {
            throw new IllegalArgumentException("state schema limits must be positive");
        }
    }

    public void validateTopLevel(Map<String, ?> values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.size() > maximumValues) {
            throw new IllegalArgumentException("workflow state exceeds maximum values");
        }
        if (!allowedKeys.containsAll(values.keySet())) {
            throw new IllegalArgumentException("workflow state contains keys outside its schema");
        }
    }
}
