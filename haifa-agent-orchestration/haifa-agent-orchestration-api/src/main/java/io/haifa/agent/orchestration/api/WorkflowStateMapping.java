package io.haifa.agent.orchestration.api;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Explicit deterministic key mapping across a restricted subgraph boundary. */
public record WorkflowStateMapping(Map<String, String> inputs, Map<String, String> outputs) {
    public WorkflowStateMapping {
        inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs must not be null"));
        validate(inputs, "input");
        validate(outputs, "output");
    }

    private static void validate(Map<String, String> mapping, String name) {
        if (mapping.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new IllegalArgumentException(name + " mapping keys must not be blank");
        }
        if (new HashSet<>(mapping.values()).size() != mapping.size()) {
            throw new IllegalArgumentException(name + " mapping targets must be unique");
        }
    }
}
