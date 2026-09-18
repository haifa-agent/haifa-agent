package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MemoryPage(List<Memory> items, Optional<MemoryPageCursor> nextCursor) {
    public MemoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
