package io.haifa.agent.core.run;

import java.util.Objects;

/** Monotonic cumulative resource usage for one run. */
public record AgentRunUsage(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long modelCalls,
        long toolCalls,
        long childRuns,
        long costMinorUnits,
        long wallTimeMillis) {

    public static final AgentRunUsage ZERO = new AgentRunUsage(0, 0, 0, 0, 0, 0, 0, 0);

    public AgentRunUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedInputTokens < 0
                || modelCalls < 0
                || toolCalls < 0
                || childRuns < 0
                || costMinorUnits < 0
                || wallTimeMillis < 0) {
            throw new IllegalArgumentException("usage values must not be negative");
        }
    }

    public AgentRunUsage plus(AgentRunUsageDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        return new AgentRunUsage(
                Math.addExact(inputTokens, delta.inputTokens()),
                Math.addExact(outputTokens, delta.outputTokens()),
                Math.addExact(cachedInputTokens, delta.cachedInputTokens()),
                Math.addExact(modelCalls, delta.modelCalls()),
                Math.addExact(toolCalls, delta.toolCalls()),
                Math.addExact(childRuns, delta.childRuns()),
                Math.addExact(costMinorUnits, delta.costMinorUnits()),
                Math.addExact(wallTimeMillis, delta.wallTimeMillis()));
    }
}
