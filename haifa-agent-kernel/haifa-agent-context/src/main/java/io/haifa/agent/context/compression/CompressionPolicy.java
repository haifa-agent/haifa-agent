package io.haifa.agent.context.compression;

/** Deterministic compaction bounds and semantic compaction configuration for a session window. */
public record CompressionPolicy(
        int recentMessageGroups,
        int maxSummaryFacts,
        int forcedRecentMessageGroups,
        int retainedTailTokenPercent,
        int forcedRetainedTailTokenPercent,
        int softTriggerHeadroomPercent,
        int minTriggerHeadroom,
        int maxTriggerHeadroom,
        int targetTailTokenPercent,
        int minTailTokens,
        int maxTailTokens,
        int maxCompactionPhysicalCalls,
        boolean allowDeterministicDegradedFallback,
        boolean semanticCompactionEnabled) {

    public CompressionPolicy(int recentMessageGroups, int maxSummaryFacts, int forcedRecentMessageGroups) {
        this(recentMessageGroups, maxSummaryFacts, forcedRecentMessageGroups, 50, 25);
    }

    public CompressionPolicy(
            int recentMessageGroups,
            int maxSummaryFacts,
            int forcedRecentMessageGroups,
            int retainedTailTokenPercent,
            int forcedRetainedTailTokenPercent) {
        this(
                recentMessageGroups,
                maxSummaryFacts,
                forcedRecentMessageGroups,
                retainedTailTokenPercent,
                forcedRetainedTailTokenPercent,
                15,
                8_000,
                32_000,
                25,
                8_000,
                24_000,
                3,
                false,
                false);
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
        if (softTriggerHeadroomPercent < 1 || softTriggerHeadroomPercent > 90) {
            throw new IllegalArgumentException("softTriggerHeadroomPercent must be between 1 and 90");
        }
        if (minTriggerHeadroom < 1 || maxTriggerHeadroom < minTriggerHeadroom) {
            throw new IllegalArgumentException("trigger headroom bounds are invalid");
        }
        if (targetTailTokenPercent < 1 || targetTailTokenPercent > 90) {
            throw new IllegalArgumentException("targetTailTokenPercent must be between 1 and 90");
        }
        if (minTailTokens < 1 || maxTailTokens < minTailTokens) {
            throw new IllegalArgumentException("tail token bounds are invalid");
        }
        if (maxCompactionPhysicalCalls < 1) {
            throw new IllegalArgumentException("maxCompactionPhysicalCalls must be positive");
        }
    }

    public static CompressionPolicy defaults() {
        return new CompressionPolicy(
                12,
                32,
                4,
                50,
                25,
                15,
                8_000,
                32_000,
                25,
                8_000,
                24_000,
                3,
                false,
                false);
    }

    public CompressionPolicy withSemanticCompactionEnabled(boolean enabled) {
        return new CompressionPolicy(
                recentMessageGroups,
                maxSummaryFacts,
                forcedRecentMessageGroups,
                retainedTailTokenPercent,
                forcedRetainedTailTokenPercent,
                softTriggerHeadroomPercent,
                minTriggerHeadroom,
                maxTriggerHeadroom,
                targetTailTokenPercent,
                minTailTokens,
                maxTailTokens,
                maxCompactionPhysicalCalls,
                allowDeterministicDegradedFallback,
                enabled);
    }

    public CompressionPolicy withDegradedFallback(boolean allowed) {
        return new CompressionPolicy(
                recentMessageGroups,
                maxSummaryFacts,
                forcedRecentMessageGroups,
                retainedTailTokenPercent,
                forcedRetainedTailTokenPercent,
                softTriggerHeadroomPercent,
                minTriggerHeadroom,
                maxTriggerHeadroom,
                targetTailTokenPercent,
                minTailTokens,
                maxTailTokens,
                maxCompactionPhysicalCalls,
                allowed,
                semanticCompactionEnabled);
    }

    public CompressionPolicy withTailTokenBounds(int minTailTokens, int maxTailTokens) {
        return new CompressionPolicy(
                recentMessageGroups,
                maxSummaryFacts,
                forcedRecentMessageGroups,
                retainedTailTokenPercent,
                forcedRetainedTailTokenPercent,
                softTriggerHeadroomPercent,
                minTriggerHeadroom,
                maxTriggerHeadroom,
                targetTailTokenPercent,
                minTailTokens,
                maxTailTokens,
                maxCompactionPhysicalCalls,
                allowDeterministicDegradedFallback,
                semanticCompactionEnabled);
    }

    public String version() {
        return "session-window-v2";
    }
}
