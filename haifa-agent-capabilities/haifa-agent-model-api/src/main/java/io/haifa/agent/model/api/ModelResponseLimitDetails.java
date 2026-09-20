package io.haifa.agent.model.api;

import java.util.Objects;

/** Content-free diagnostics for a provider response size-limit failure. */
public record ModelResponseLimitDetails(
        ModelResponseLimitKind limitKind, long limitBytes, long observedBytes, int attempt) {
    public ModelResponseLimitDetails {
        limitKind = Objects.requireNonNull(limitKind, "limitKind must not be null");
        if (limitBytes < 1) throw new IllegalArgumentException("limitBytes must be positive");
        if (observedBytes <= limitBytes) {
            throw new IllegalArgumentException("observedBytes must exceed limitBytes");
        }
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
    }
}
