package io.haifa.agent.model.api;

import java.util.Objects;

/** Short-lived resolved secret whose string representation is always redacted. */
public final class ResolvedCredential {
    private final String value;

    public ResolvedCredential(String value) {
        this.value = ModelValues.text(value, "credential");
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "ResolvedCredential[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return Objects.hash("REDACTED");
    }
}
