package io.haifa.agent.runtime.core.compaction;

/**
 * Unified context token budget breakdown across trigger evaluation and context assembly.
 */
public record ContextBudgetBreakdown(
        long contextWindowTokens,
        long outputReserveTokens,
        long safetyMarginTokens,
        long fixedPrefixTokens,
        long otherSourceTokens,
        long availableSessionTokens,
        long triggerHeadroomTokens,
        long softLimitTokens,
        long currentSessionTokens,
        long resolvedTailTokens) {

    public ContextBudgetBreakdown(
            long contextWindowTokens,
            long outputReserveTokens,
            long safetyMarginTokens,
            long fixedPrefixTokens,
            long otherSourceTokens,
            long availableSessionTokens,
            long triggerHeadroomTokens,
            long softLimitTokens,
            long currentSessionTokens) {
        this(
                contextWindowTokens,
                outputReserveTokens,
                safetyMarginTokens,
                fixedPrefixTokens,
                otherSourceTokens,
                availableSessionTokens,
                triggerHeadroomTokens,
                softLimitTokens,
                currentSessionTokens,
                0L);
    }

    public long resolvedActiveHistoryBudgetTokens() {
        return softLimitTokens;
    }

    public long resolvedRetainedTailTokens() {
        return resolvedTailTokens;
    }
}
