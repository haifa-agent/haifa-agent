package io.haifa.agent.runtime.api;

import java.util.Objects;
import java.util.Set;

/** Forward-compatible public interaction kind. */
public record InteractionKind(String value) {
    public static final InteractionKind CLARIFICATION = new InteractionKind("clarification");
    public static final InteractionKind CONFIRMATION = new InteractionKind("confirmation");
    public static final InteractionKind SELECTION = new InteractionKind("selection");
    public static final InteractionKind INPUT_REQUIRED = new InteractionKind("input-required");
    public static final InteractionKind ARTIFACT_REVIEW = new InteractionKind("artifact-review");
    public static final InteractionKind CONFLICT_RESOLUTION = new InteractionKind("conflict-resolution");
    public static final InteractionKind APPROVAL = new InteractionKind("approval");

    private static final Set<String> KNOWN = Set.of(
            CLARIFICATION.value,
            CONFIRMATION.value,
            SELECTION.value,
            INPUT_REQUIRED.value,
            ARTIFACT_REVIEW.value,
            CONFLICT_RESOLUTION.value,
            APPROVAL.value);

    public InteractionKind {
        value = requireToken(value, "value");
    }

    public boolean known() {
        return KNOWN.contains(value);
    }

    static String requireToken(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(field + " must be a bounded lower-kebab token");
        }
        return normalized;
    }
}
