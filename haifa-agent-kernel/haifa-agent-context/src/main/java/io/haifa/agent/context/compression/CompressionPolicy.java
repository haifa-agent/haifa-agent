package io.haifa.agent.context.compression;

/** Deterministic window and summary bounds. */
public record CompressionPolicy(int recentMessageGroups, int maxSummaryFacts, int forcedRecentMessageGroups) {
    public CompressionPolicy {
        if (recentMessageGroups < 1 || maxSummaryFacts < 1 || forcedRecentMessageGroups < 1) {
            throw new IllegalArgumentException("compression policy limits must be positive");
        }
        if (forcedRecentMessageGroups > recentMessageGroups) {
            throw new IllegalArgumentException("forced recent window must not be larger than normal window");
        }
    }

    public static CompressionPolicy defaults() {
        return new CompressionPolicy(12, 32, 4);
    }

    public String version() {
        return "session-window-v1";
    }
}
