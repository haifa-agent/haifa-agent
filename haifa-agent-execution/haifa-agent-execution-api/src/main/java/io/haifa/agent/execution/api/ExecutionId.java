package io.haifa.agent.execution.api;

import java.util.Objects;

public record ExecutionId(String value) {
    public ExecutionId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
