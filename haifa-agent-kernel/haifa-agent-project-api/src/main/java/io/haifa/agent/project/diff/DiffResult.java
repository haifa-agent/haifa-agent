package io.haifa.agent.project.diff;

import java.util.Objects;

public record DiffResult(String unifiedDiff, int fileCount, int outputBytes, String sha256) {
    public DiffResult {
        unifiedDiff = Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
        if (fileCount < 0 || outputBytes < 0) throw new IllegalArgumentException("counts must not be negative");
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
    }

    @Override
    public String toString() {
        return "DiffResult[fileCount=" + fileCount + ", outputBytes=" + outputBytes + ", sha256=" + sha256 + "]";
    }
}
