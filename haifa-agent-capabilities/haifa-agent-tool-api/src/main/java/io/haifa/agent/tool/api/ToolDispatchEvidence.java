package io.haifa.agent.tool.api;

import java.util.Objects;
import java.util.OptionalLong;

/** Safe, bounded identity persisted at the irreversible tool dispatch boundary. */
public record ToolDispatchEvidence(String executionId, OptionalLong processId, String workingDirectoryDigest) {
    public ToolDispatchEvidence {
        executionId = text(executionId, "executionId", 256);
        processId = Objects.requireNonNull(processId, "processId must not be null");
        if (processId.isPresent() && processId.getAsLong() < 1) {
            throw new IllegalArgumentException("processId must be positive");
        }
        workingDirectoryDigest = text(workingDirectoryDigest, "workingDirectoryDigest", 64);
        if (!workingDirectoryDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("workingDirectoryDigest must be a lowercase SHA-256 digest");
        }
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain bounded text");
        }
        return normalized;
    }
}
