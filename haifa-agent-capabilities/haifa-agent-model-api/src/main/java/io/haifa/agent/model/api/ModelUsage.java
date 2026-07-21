package io.haifa.agent.model.api;

/** Provider-reported token use plus optional priced cost. */
public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheHitTokens,
        long cacheMissTokens,
        long reasoningTokens,
        boolean costKnown,
        long costMinorUnits) {
    public ModelUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cacheHitTokens < 0
                || cacheMissTokens < 0
                || reasoningTokens < 0
                || costMinorUnits < 0) {
            throw new IllegalArgumentException("model usage must not be negative");
        }
        if (!costKnown && costMinorUnits != 0) {
            throw new IllegalArgumentException("unknown cost must not have a value");
        }
    }

    public static ModelUsage unpriced(long inputTokens, long outputTokens) {
        return new ModelUsage(inputTokens, outputTokens, 0, 0, 0, false, 0);
    }
}
