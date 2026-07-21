package io.haifa.agent.git;

import java.util.Objects;

public record GitDiff(String unifiedDiff, boolean truncated, int byteCount) {
    public GitDiff {
        unifiedDiff = Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
    }

    @Override
    public String toString() {
        return "GitDiff[bytes=" + byteCount + ", truncated=" + truncated + "]";
    }
}
