package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.tool.ToolCallId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable plan specifying wire-level tool payload pruning and argument truncation
 * for model message assembly.
 */
public record ModelMessageProjectionPlan(
        Set<ToolCallId> prunedToolResults,
        Map<ToolCallId, Map<String, Object>> truncatedToolCalls,
        long rawActiveTokens,
        long projectedActiveTokens,
        long tokensSavedByPruning,
        boolean bypassCompactionRecommended) {

    public static final ModelMessageProjectionPlan EMPTY =
            new ModelMessageProjectionPlan(Set.of(), Map.of(), 0L, 0L, 0L, false);

    public ModelMessageProjectionPlan {
        prunedToolResults = Set.copyOf(Objects.requireNonNull(prunedToolResults, "prunedToolResults must not be null"));
        truncatedToolCalls =
                Map.copyOf(Objects.requireNonNull(truncatedToolCalls, "truncatedToolCalls must not be null"));
    }

    public boolean hasPrunedItems() {
        return !prunedToolResults.isEmpty() || !truncatedToolCalls.isEmpty();
    }

    public boolean isToolResultPruned(ToolCallId callId) {
        return prunedToolResults.contains(callId);
    }

    public boolean isToolCallTruncated(ToolCallId callId) {
        return truncatedToolCalls.containsKey(callId);
    }

    public Map<String, Object> truncatedArguments(ToolCallId callId) {
        return truncatedToolCalls.get(callId);
    }
}
