package io.haifa.agent.project.mutation;

import java.util.Objects;

public record WorkspaceMutationCapabilities(
        boolean atomicMoveAttempted, boolean durableFileFlush, String caseSensitivity) {
    public WorkspaceMutationCapabilities {
        caseSensitivity = Objects.requireNonNull(caseSensitivity, "caseSensitivity must not be null")
                .trim();
        if (caseSensitivity.isEmpty()) throw new IllegalArgumentException("caseSensitivity must not be blank");
    }
}
