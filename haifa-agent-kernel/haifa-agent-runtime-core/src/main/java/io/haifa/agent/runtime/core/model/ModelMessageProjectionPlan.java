package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable plan describing which historical Tool results are replaced by the omission notice on the wire.
 *
 * <p>The plan carries only what consumers need: the pruned Tool Call ids and the token estimate of the wire
 * representation before and after projection. It never mutates authoritative calls and never fabricates arguments.
 */
public record ModelMessageProjectionPlan(
        Set<ToolCallId> prunedToolResults,
        long rawActiveTokens,
        long projectedActiveTokens,
        long tokensSavedByPruning) {

    public static final ModelMessageProjectionPlan EMPTY = new ModelMessageProjectionPlan(Set.of(), 0L, 0L, 0L);

    public ModelMessageProjectionPlan {
        prunedToolResults = Set.copyOf(Objects.requireNonNull(prunedToolResults, "prunedToolResults must not be null"));
        if (rawActiveTokens < 0 || projectedActiveTokens < 0 || tokensSavedByPruning < 0) {
            throw new IllegalArgumentException("projection token counters must not be negative");
        }
    }

    public boolean hasPrunedItems() {
        return !prunedToolResults.isEmpty();
    }

    public boolean isToolResultPruned(ToolCallId callId) {
        return prunedToolResults.contains(callId);
    }
}
