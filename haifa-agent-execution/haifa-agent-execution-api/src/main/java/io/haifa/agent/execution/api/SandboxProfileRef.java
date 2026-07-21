package io.haifa.agent.execution.api;

import java.util.Objects;

public record SandboxProfileRef(String value, String version) {
    public SandboxProfileRef {
        value = require(value, "value");
        version = require(version, "version");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
