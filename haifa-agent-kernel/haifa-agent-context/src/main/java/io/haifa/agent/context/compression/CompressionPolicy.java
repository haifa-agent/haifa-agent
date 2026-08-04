package io.haifa.agent.context.compression;

/** Deterministic compaction bounds for an append-only session window. */
public record CompressionPolicy(
        int recentMessageGroups,
        int maxSummaryFacts,
        int forcedRecentMessageGroups,
        int retainedTailTokenPercent,
        int forcedRetainedTailTokenPercent) {
    public CompressionPolicy(int recentMessageGroups, int maxSummaryFacts, int forcedRecentMessageGroups) {
        this(recentMessageGroups, maxSummaryFacts, forcedRecentMessageGroups, 50, 25);
    }

    public CompressionPolicy {
        if (recentMessageGroups < 1 || maxSummaryFacts < 1 || forcedRecentMessageGroups < 1) {
            throw new IllegalArgumentException("compression policy limits must be positive");
        }
        if (forcedRecentMessageGroups > recentMessageGroups) {
            throw new IllegalArgumentException("forced recent window must not be larger than normal window");
        }
        if (retainedTailTokenPercent < 1
                || retainedTailTokenPercent > 99
                || forcedRetainedTailTokenPercent < 1
                || forcedRetainedTailTokenPercent > retainedTailTokenPercent) {
            throw new IllegalArgumentException("retained tail token percentages are invalid");
        }
    }

    public static CompressionPolicy defaults() {
        return new CompressionPolicy(12, 32, 4, 50, 25);
    }

    public String version() {
        return "session-window-v2";
    }
}
