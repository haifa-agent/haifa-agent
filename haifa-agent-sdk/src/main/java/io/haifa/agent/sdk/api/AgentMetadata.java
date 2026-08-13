package io.haifa.agent.sdk.api;

import java.util.Objects;

/** Immutable host-declared display metadata that does not affect Agent execution semantics. */
public record AgentMetadata(String name) {
    public static final String DEFAULT_NAME = "haifa-agent";

    public AgentMetadata {
        name = requireText(name, "name", 128);
    }

    public static AgentMetadata defaults() {
        return new AgentMetadata(DEFAULT_NAME);
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
