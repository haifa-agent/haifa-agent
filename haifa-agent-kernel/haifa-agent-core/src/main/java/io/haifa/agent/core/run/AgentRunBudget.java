package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Locale;

/** Consumable resource and cost ceilings for one logical run. */
public record AgentRunBudget(
        QuotaMode quotaMode,
        long maxInputTokens,
        long maxOutputTokens,
        long maxCachedInputTokens,
        long maxToolCalls,
        long maxModelCalls,
        long maxChildRuns,
        String maxCostCurrency,
        long maxCostMinorUnits) {

    public AgentRunBudget {
        quotaMode = quotaMode == null ? QuotaMode.HARD_STOP : quotaMode;
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

    public AgentRunBudget(
            long maxInputTokens,
            long maxOutputTokens,
            long maxCachedInputTokens,
            long maxToolCalls,
            long maxModelCalls,
            long maxChildRuns,
            String maxCostCurrency,
            long maxCostMinorUnits) {
        this(
                (maxInputTokens == 0 && maxOutputTokens == 0 && maxCostMinorUnits == 0)
                        ? QuotaMode.DISABLED
                        : QuotaMode.HARD_STOP,
                maxInputTokens,
                maxOutputTokens,
                maxCachedInputTokens,
                maxToolCalls,
                maxModelCalls,
                maxChildRuns,
                maxCostCurrency,
                maxCostMinorUnits);
    }

    public static AgentRunBudget disabled() {
        return new AgentRunBudget(QuotaMode.DISABLED, 0, 0, 0, 0, 0, 0, "USD", 0);
    }

    public static AgentRunBudget observeOnly(
            long maxInputTokens,
            long maxOutputTokens,
            long maxCachedInputTokens,
            String currency,
            long maxCostMinorUnits) {
        return new AgentRunBudget(
                QuotaMode.OBSERVE_ONLY,
                maxInputTokens,
                maxOutputTokens,
                maxCachedInputTokens,
                0,
                0,
                0,
                currency,
                maxCostMinorUnits);
    }

    public static AgentRunBudget hardStop(
            long maxInputTokens,
            long maxOutputTokens,
            long maxCachedInputTokens,
            String currency,
            long maxCostMinorUnits) {
        return new AgentRunBudget(
                QuotaMode.HARD_STOP,
                maxInputTokens,
                maxOutputTokens,
                maxCachedInputTokens,
                0,
                0,
                0,
                currency,
                maxCostMinorUnits);
    }

    public QuotaPolicy toQuotaPolicy() {
        return new QuotaPolicy(
                quotaMode,
                maxInputTokens > 0 ? maxInputTokens : null,
                maxOutputTokens > 0 ? maxOutputTokens : null,
                maxCachedInputTokens > 0 ? maxCachedInputTokens : null,
                maxCostCurrency,
                maxCostMinorUnits > 0 ? maxCostMinorUnits : null);
    }

    public boolean isExceededBy(AgentRunUsage usage) {
        if (usage == null) return false;
        if (quotaMode == QuotaMode.HARD_STOP) {
            if (maxInputTokens > 0 && usage.inputTokens() > maxInputTokens) return true;
            if (maxOutputTokens > 0 && usage.outputTokens() > maxOutputTokens) return true;
            if (maxCachedInputTokens > 0 && usage.cachedInputTokens() > maxCachedInputTokens) return true;
            if (maxCostMinorUnits > 0 && usage.costMinorUnits() > maxCostMinorUnits) return true;
        }
        if (maxToolCalls > 0 && usage.toolCalls() > maxToolCalls) return true;
        if (maxModelCalls > 0 && usage.modelCalls() > maxModelCalls) return true;
        if (maxChildRuns > 0 && usage.childRuns() > maxChildRuns) return true;
        return false;
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("run budget values must not be negative");
            }
        }
    }
}
