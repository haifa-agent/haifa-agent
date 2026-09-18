package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.context.compression.CompressionPolicy;
import java.util.Objects;

/**
 * Evaluates whether conversation compaction should be triggered based on context token budget,
 * soft limit headroom, and usage anchors.
 */
public final class CompactionTriggerEvaluator {

    private final CompressionPolicy policy;

    public CompactionTriggerEvaluator(CompressionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public ContextBudgetBreakdown calculateBreakdown(
            long contextWindowTokens,
            long outputReserveTokens,
            long fixedPrefixTokens,
            long otherSourceTokens,
            long currentSessionTokens) {
        long safetyMarginTokens = Math.min(16_384L, Math.max(256L, contextWindowTokens / 20L));
        long availableSessionTokens = Math.max(
                0L,
                contextWindowTokens - outputReserveTokens - safetyMarginTokens - fixedPrefixTokens - otherSourceTokens);
        long calculatedHeadroom = (availableSessionTokens * policy.softTriggerHeadroomPercent()) / 100L;
        long triggerHeadroomTokens =
                Math.clamp(calculatedHeadroom, (long) policy.minTriggerHeadroom(), (long) policy.maxTriggerHeadroom());
        long capacitySoftLimit = computeCapacitySoftLimit(availableSessionTokens, triggerHeadroomTokens);
        long activeBudget;
        if (policy.activeHistoryBudgetTokens().isPresent()) {
            activeBudget = policy.activeHistoryBudgetTokens().getAsLong();
        } else if (policy.activeHistoryBudgetPercent() > 0) {
            long ratioBudget = (availableSessionTokens * policy.activeHistoryBudgetPercent()) / 100L;
            activeBudget = Math.clamp(
                    ratioBudget, policy.minActiveHistoryBudgetTokens(), policy.maxActiveHistoryBudgetTokens());
        } else {
            activeBudget = capacitySoftLimit;
        }
        long softLimitTokens = Math.min(capacitySoftLimit, activeBudget);
        long resolvedActiveBudget = softLimitTokens > 0 ? softLimitTokens : availableSessionTokens;
        long calculatedTail = (resolvedActiveBudget * policy.targetTailTokenPercent()) / 100L;
        long clampedTail = Math.clamp(calculatedTail, (long) policy.minTailTokens(), (long) policy.maxTailTokens());
        long resolvedTailTokens = Math.min(clampedTail, resolvedActiveBudget);

        return new ContextBudgetBreakdown(
                contextWindowTokens,
                outputReserveTokens,
                safetyMarginTokens,
                fixedPrefixTokens,
                otherSourceTokens,
                availableSessionTokens,
                triggerHeadroomTokens,
                softLimitTokens,
                currentSessionTokens,
                resolvedTailTokens);
    }

    public CompactionTriggerDecision evaluate(
            long contextWindowTokens,
            long outputReserveTokens,
            long fixedPrefixTokens,
            long otherSourceTokens,
            long currentSessionTokens,
            int turnCount) {
        ContextBudgetBreakdown breakdown = calculateBreakdown(
                contextWindowTokens, outputReserveTokens, fixedPrefixTokens, otherSourceTokens, currentSessionTokens);

        if (!policy.semanticCompactionEnabled()) {
            return CompactionTriggerDecision.doNotCompact(breakdown);
        }

        // Need at least 2 turns to be able to compact older turns while retaining tail
        if (turnCount >= 2 && currentSessionTokens >= breakdown.softLimitTokens()) {
            long capacitySoftLimit =
                    computeCapacitySoftLimit(breakdown.availableSessionTokens(), breakdown.triggerHeadroomTokens());
            CompactionTriggerReason reason = CompactionTriggerReason.SOFT_TOKEN_THRESHOLD;
            boolean hasActiveBudget =
                    policy.activeHistoryBudgetTokens().isPresent() || policy.activeHistoryBudgetPercent() > 0;
            if (hasActiveBudget
                    && breakdown.softLimitTokens() < capacitySoftLimit
                    && currentSessionTokens >= breakdown.softLimitTokens()
                    && currentSessionTokens < capacitySoftLimit) {
                reason = CompactionTriggerReason.ACTIVE_HISTORY_BUDGET;
            }
            return CompactionTriggerDecision.compact(reason, breakdown);
        }

        return CompactionTriggerDecision.doNotCompact(breakdown);
    }

    private static long computeCapacitySoftLimit(long availableSessionTokens, long triggerHeadroomTokens) {
        return Math.max(0L, availableSessionTokens - triggerHeadroomTokens);
    }
}
