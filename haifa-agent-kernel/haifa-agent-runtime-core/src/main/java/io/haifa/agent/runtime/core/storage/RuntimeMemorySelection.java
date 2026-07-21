package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointRef;
import java.util.List;
import java.util.Objects;

/** Safe retrieval inputs and references for checkpoint reproducibility. */
public record RuntimeMemorySelection(
        List<MemoryCheckpointRef> memories, String retrievalPolicyVersion, String queryDigest) {
    public static final RuntimeMemorySelection EMPTY =
            new RuntimeMemorySelection(List.of(), "memory-governance-v1", "sha256:none");

    public RuntimeMemorySelection {
        memories = List.copyOf(Objects.requireNonNull(memories));
        retrievalPolicyVersion = requireText(retrievalPolicyVersion, "retrievalPolicyVersion");
        queryDigest = requireText(queryDigest, "queryDigest");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
