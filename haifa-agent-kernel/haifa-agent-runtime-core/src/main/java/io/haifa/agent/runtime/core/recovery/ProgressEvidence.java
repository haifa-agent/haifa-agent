package io.haifa.agent.runtime.core.recovery;

import java.util.List;
import java.util.Objects;

public record ProgressEvidence(Type type, String safeDigest) {
    public ProgressEvidence(Type type, String stableReference, boolean hashReference) {
        this(
                Objects.requireNonNull(type, "type must not be null"),
                hashReference
                        ? FailureFingerprint.digest(
                                List.of(Objects.requireNonNull(stableReference, "stableReference must not be null")))
                        : stableReference);
    }

    public ProgressEvidence {
        type = Objects.requireNonNull(type, "type must not be null");
        if (!safeDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("progress evidence must use a safe SHA-256 digest");
        }
    }

    public enum Type {
        WORKSPACE_CHANGE,
        ARTIFACT_CHANGE,
        TODO_ADVANCE,
        VALIDATION_ADVANCE,
        BLOCKER_REMOVED,
        INTERACTION_SUPPLIED,
        CHILD_RESULT_AVAILABLE
    }
}
