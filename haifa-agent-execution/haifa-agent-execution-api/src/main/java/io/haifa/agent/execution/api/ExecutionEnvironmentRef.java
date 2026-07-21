package io.haifa.agent.execution.api;

import java.util.List;
import java.util.Objects;

public record ExecutionEnvironmentRef(List<String> leaseRefs) {
    public ExecutionEnvironmentRef {
        leaseRefs = List.copyOf(Objects.requireNonNull(leaseRefs, "leaseRefs must not be null"));
        leaseRefs.forEach(value -> {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("lease ref must not be blank");
        });
    }

    public static ExecutionEnvironmentRef empty() {
        return new ExecutionEnvironmentRef(List.of());
    }
}
