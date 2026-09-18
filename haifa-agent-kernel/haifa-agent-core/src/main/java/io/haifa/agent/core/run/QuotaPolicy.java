package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Locale;
import java.util.Objects;

/** Optional cumulative token and cost quota governance policy. */
public record QuotaPolicy(
        QuotaMode mode,
        Long maxInputTokens,
        Long maxOutputTokens,
        Long maxCachedInputTokens,
        String maxCostCurrency,
        Long maxCostMinorUnits) {

    public QuotaPolicy {
        mode = Objects.requireNonNull(mode, "quota mode must not be null");
        validateNonNegative("maxInputTokens", maxInputTokens);
        validateNonNegative("maxOutputTokens", maxOutputTokens);
        validateNonNegative("maxCachedInputTokens", maxCachedInputTokens);
        validateNonNegative("maxCostMinorUnits", maxCostMinorUnits);
        maxCostCurrency = maxCostCurrency == null || maxCostCurrency.isBlank()
                ? "USD"
                : requireText(maxCostCurrency, "maxCostCurrency").toUpperCase(Locale.ROOT);
    }

    public static QuotaPolicy disabled() {
        return new QuotaPolicy(QuotaMode.DISABLED, null, null, null, "USD", null);
    }

    public static QuotaPolicy observeOnly() {
        return new QuotaPolicy(QuotaMode.OBSERVE_ONLY, null, null, null, "USD", null);
    }

    public static QuotaPolicy hardStop(
            Long maxInputTokens,
            Long maxOutputTokens,
            Long maxCachedInputTokens,
            String maxCostCurrency,
            Long maxCostMinorUnits) {
        return new QuotaPolicy(
                QuotaMode.HARD_STOP,
                maxInputTokens,
                maxOutputTokens,
                maxCachedInputTokens,
                maxCostCurrency,
                maxCostMinorUnits);
    }

    public boolean isExceededBy(AgentRunUsage usage) {
        if (mode != QuotaMode.HARD_STOP || usage == null) {
            return false;
        }
        return (maxInputTokens != null && maxInputTokens > 0 && usage.inputTokens() > maxInputTokens)
                || (maxOutputTokens != null && maxOutputTokens > 0 && usage.outputTokens() > maxOutputTokens)
                || (maxCachedInputTokens != null
                        && maxCachedInputTokens > 0
                        && usage.cachedInputTokens() > maxCachedInputTokens)
                || (maxCostMinorUnits != null && maxCostMinorUnits > 0 && usage.costMinorUnits() > maxCostMinorUnits);
    }

    private static void validateNonNegative(String name, Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
