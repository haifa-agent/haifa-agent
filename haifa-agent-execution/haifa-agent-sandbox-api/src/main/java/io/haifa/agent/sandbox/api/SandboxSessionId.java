package io.haifa.agent.sandbox.api;

import java.util.Objects;

public record SandboxSessionId(String value) {
    public SandboxSessionId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
