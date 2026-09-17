package io.haifa.agent.runtime.core.compaction;

/**
 * Immutable outcome of a conversation compaction evaluation.
 * Carries structured telemetry metrics for both compaction events and trace reporting.
 */
public record CompactionEvaluationOutcome(
        CompactionTriggerReason triggerReason,
        boolean tier1PruningBypassedSummary,
        long projectedActiveHistoryTokensBefore,
        long projectedActiveHistoryTokensAfter,
        long omittedToolPayloadTokens,
        int omittedToolResultCount,
        double compactionSummaryCacheHitRate,
        long compactionEvaluationElapsedMillis,
        boolean compacted) {

    public static final CompactionEvaluationOutcome NONE =
            new CompactionEvaluationOutcome(null, false, 0L, 0L, 0L, 0, 0.0, 0L, false);

    public String semanticCompactionReason() {
        return triggerReason != null ? triggerReason.name() : "NONE";
    }

    public static CompactionEvaluationOutcome untriggered(long estimatedTokens, long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                null, false, estimatedTokens, estimatedTokens, 0L, 0, 0.0, elapsedMillis, false);
    }

    public static CompactionEvaluationOutcome bypassed(
            CompactionTriggerReason reason,
            long rawTokens,
            long projectedTokens,
            long tokensSaved,
            int prunedCount,
            long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                reason, true, rawTokens, projectedTokens, tokensSaved, prunedCount, 0.0, elapsedMillis, false);
    }

    public static CompactionEvaluationOutcome compacted(
            CompactionTriggerReason reason,
            long beforeTokens,
            long afterTokens,
            long omittedTokens,
            int omittedCount,
            double cacheHitRate,
            long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                reason,
                false,
                beforeTokens,
                afterTokens,
                omittedTokens,
                omittedCount,
                cacheHitRate,
                elapsedMillis,
                true);
    }

    public static CompactionEvaluationOutcome failed(
            CompactionTriggerReason reason,
            long beforeTokens,
            long omittedTokens,
            int omittedCount,
            double cacheHitRate,
            long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                reason,
                false,
                beforeTokens,
                beforeTokens,
                omittedTokens,
                omittedCount,
                cacheHitRate,
                elapsedMillis,
                false);
    }
}
