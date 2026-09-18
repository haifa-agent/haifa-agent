package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.run.AgentRunUsage;

/** Constructor-independent representation of cumulative run usage. */
public record RunUsagePayload(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long modelCalls,
        long toolCalls,
        long childRuns,
        long costMinorUnits,
        long wallTimeMillis) {

    public static RunUsagePayload from(AgentRunUsage value) {
        return new RunUsagePayload(
                value.inputTokens(),
                value.outputTokens(),
                value.cachedInputTokens(),
                value.modelCalls(),
                value.toolCalls(),
                value.childRuns(),
                value.costMinorUnits(),
                value.wallTimeMillis());
    }

    public AgentRunUsage toDomain() {
        return new AgentRunUsage(
                inputTokens,
                outputTokens,
                cachedInputTokens,
                modelCalls,
                toolCalls,
                childRuns,
                costMinorUnits,
                wallTimeMillis);
    }
}
