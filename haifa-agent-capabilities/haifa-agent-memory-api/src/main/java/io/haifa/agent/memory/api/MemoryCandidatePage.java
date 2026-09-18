package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MemoryCandidatePage(List<MemoryCandidate> items, Optional<MemoryPageCursor> nextCursor) {
    public MemoryCandidatePage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
