package io.haifa.agent.context.compression;

import java.util.OptionalLong;

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
        boolean semanticCompactionEnabled,
        int activeHistoryBudgetPercent,
        long minActiveHistoryBudgetTokens,
        long maxActiveHistoryBudgetTokens,
        OptionalLong activeHistoryBudgetTokens) {

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
                true,
                OptionalLong.empty());
    }

    public CompressionPolicy(
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
        this(
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
                semanticCompactionEnabled,
                OptionalLong.empty());
    }

    public CompressionPolicy(
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
            boolean semanticCompactionEnabled,
            OptionalLong activeHistoryBudgetTokens) {
        this(
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
                semanticCompactionEnabled,
                0,
                0L,
                Long.MAX_VALUE,
                activeHistoryBudgetTokens);
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
        if (activeHistoryBudgetPercent < 0 || activeHistoryBudgetPercent > 100) {
            throw new IllegalArgumentException("activeHistoryBudgetPercent must be between 0 and 100");
        }
        if (minActiveHistoryBudgetTokens < 0 || maxActiveHistoryBudgetTokens < minActiveHistoryBudgetTokens) {
            throw new IllegalArgumentException("active history budget bounds are invalid");
        }
        if (activeHistoryBudgetTokens == null) {
            activeHistoryBudgetTokens = OptionalLong.empty();
        }
        if (activeHistoryBudgetTokens.isPresent() && activeHistoryBudgetTokens.getAsLong() < 1) {
            throw new IllegalArgumentException("activeHistoryBudgetTokens must be positive");
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
                true,
                0,
                0L,
                Long.MAX_VALUE,
                OptionalLong.empty());
    }

    public CompressionPolicy withActiveHistoryBudgetTokens(long tokens) {
        if (tokens < 1) {
            throw new IllegalArgumentException("activeHistoryBudgetTokens must be positive");
        }
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
                semanticCompactionEnabled,
                activeHistoryBudgetPercent,
                minActiveHistoryBudgetTokens,
                maxActiveHistoryBudgetTokens,
                OptionalLong.of(tokens));
    }

    public CompressionPolicy withDynamicActiveBudget(int percent, long minTokens, long maxTokens) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }
        if (minTokens < 0 || maxTokens < minTokens) {
            throw new IllegalArgumentException("budget bounds are invalid");
        }
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
                semanticCompactionEnabled,
                percent,
                minTokens,
                maxTokens,
                OptionalLong.empty());
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
                enabled,
                activeHistoryBudgetPercent,
                minActiveHistoryBudgetTokens,
                maxActiveHistoryBudgetTokens,
                activeHistoryBudgetTokens);
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
                semanticCompactionEnabled,
                activeHistoryBudgetPercent,
                minActiveHistoryBudgetTokens,
                maxActiveHistoryBudgetTokens,
                activeHistoryBudgetTokens);
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
                semanticCompactionEnabled,
                activeHistoryBudgetPercent,
                minActiveHistoryBudgetTokens,
                maxActiveHistoryBudgetTokens,
                activeHistoryBudgetTokens);
    }

    public CompressionPolicy withTargetTailTokenPercent(int percent) {
        return new CompressionPolicy(
                recentMessageGroups,
                maxSummaryFacts,
                forcedRecentMessageGroups,
                retainedTailTokenPercent,
                forcedRetainedTailTokenPercent,
                softTriggerHeadroomPercent,
                minTriggerHeadroom,
                maxTriggerHeadroom,
                percent,
                minTailTokens,
                maxTailTokens,
                maxCompactionPhysicalCalls,
                allowDeterministicDegradedFallback,
                semanticCompactionEnabled,
                activeHistoryBudgetPercent,
                minActiveHistoryBudgetTokens,
                maxActiveHistoryBudgetTokens,
                activeHistoryBudgetTokens);
    }

    public String version() {
        return "session-window-v3";
    }
}
