package io.haifa.agent.core.run;

/** Non-negative resource increment recorded atomically against a run. */
public record AgentRunUsageDelta(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long modelCalls,
        long toolCalls,
        long childRuns,
        long costMinorUnits,
        long wallTimeMillis) {
    public AgentRunUsageDelta {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedInputTokens < 0
                || modelCalls < 0
                || toolCalls < 0
                || childRuns < 0
                || costMinorUnits < 0
                || wallTimeMillis < 0) {
            throw new IllegalArgumentException("usage delta values must not be negative");
        }
    }
}
