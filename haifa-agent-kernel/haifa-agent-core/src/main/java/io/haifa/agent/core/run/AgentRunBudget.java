package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Locale;

/** Consumable resource and cost ceilings for one logical run. */
public record AgentRunBudget(
        long maxInputTokens,
        long maxOutputTokens,
        long maxCachedInputTokens,
        long maxToolCalls,
        long maxModelCalls,
        long maxChildRuns,
        String maxCostCurrency,
        long maxCostMinorUnits) {

    public AgentRunBudget {
        requireNonNegative(
                maxInputTokens,
                maxOutputTokens,
                maxCachedInputTokens,
                maxToolCalls,
                maxModelCalls,
                maxChildRuns,
                maxCostMinorUnits);
        maxCostCurrency = requireText(maxCostCurrency, "maxCostCurrency").toUpperCase(Locale.ROOT);
    }

    public boolean isExceededBy(AgentRunUsage usage) {
        return usage.inputTokens() > maxInputTokens
                || usage.outputTokens() > maxOutputTokens
                || usage.cachedInputTokens() > maxCachedInputTokens
                || usage.toolCalls() > maxToolCalls
                || usage.modelCalls() > maxModelCalls
                || usage.childRuns() > maxChildRuns
                || usage.costMinorUnits() > maxCostMinorUnits;
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("run budget values must not be negative");
            }
        }
    }
}
