package io.haifa.agent.memory.api;

import java.util.Objects;

public record MemorySearchResult(Memory memory, int relevanceScore, int estimatedTokens, String selectionReason) {
    public MemorySearchResult {
        memory = Objects.requireNonNull(memory);
        if (relevanceScore < 0 || estimatedTokens < 1) throw new IllegalArgumentException("invalid search metrics");
        selectionReason = MemoryValues.text(selectionReason, "selectionReason", 256);
    }
}
