package io.haifa.agent.runtime.core.compaction;

/**
 * Immutable outcome of a conversation compaction evaluation.
 * Carries structured telemetry metrics for both compaction events and trace reporting.
 */
public record CompactionEvaluationOutcome(
        boolean evaluationPerformed,
        CompactionTriggerReason triggerReason,
        boolean tier1PruningBypassedSummary,
        long projectedActiveHistoryTokensBefore,
        long projectedActiveHistoryTokensAfter,
        long omittedToolPayloadTokens,
        int omittedToolResultCount,
        double compactionSummaryCacheHitRate,
        long compactionEvaluationElapsedMillis,
        boolean compacted,
        boolean degradedOrFailed) {

    public static final CompactionEvaluationOutcome NONE =
            new CompactionEvaluationOutcome(false, null, false, 0L, 0L, 0L, 0, 0.0, 0L, false, false);

    public String semanticCompactionReason() {
        return triggerReason != null ? triggerReason.name() : "NONE";
    }

    public static CompactionEvaluationOutcome untriggered(long estimatedTokens, long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                true, null, false, estimatedTokens, estimatedTokens, 0L, 0, 0.0, elapsedMillis, false, false);
    }

    public static CompactionEvaluationOutcome bypassed(
            CompactionTriggerReason reason,
            long rawTokens,
            long projectedTokens,
            long tokensSaved,
            int prunedCount,
            long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                true,
                reason,
                true,
                rawTokens,
                projectedTokens,
                tokensSaved,
                prunedCount,
                0.0,
                elapsedMillis,
                false,
                false);
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
                true,
                reason,
                false,
                beforeTokens,
                afterTokens,
                omittedTokens,
                omittedCount,
                cacheHitRate,
                elapsedMillis,
                true,
                false);
    }

    public static CompactionEvaluationOutcome failed(
            CompactionTriggerReason reason,
            long beforeTokens,
            long omittedTokens,
            int omittedCount,
            double cacheHitRate,
            long elapsedMillis) {
        return new CompactionEvaluationOutcome(
                true,
                reason,
                false,
                beforeTokens,
                beforeTokens,
                omittedTokens,
                omittedCount,
                cacheHitRate,
                elapsedMillis,
                false,
                true);
    }
}
