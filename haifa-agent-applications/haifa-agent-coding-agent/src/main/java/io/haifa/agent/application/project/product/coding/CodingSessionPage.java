package io.haifa.agent.application.project.product.coding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CodingSessionPage(
        List<CodingSessionSummary> items, Optional<CodingSessionCursor> nextCursor, boolean hasMore) {
    public CodingSessionPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        if (hasMore && nextCursor.isEmpty()) {
            throw new IllegalArgumentException("a continued page requires a next cursor");
        }
    }
}
