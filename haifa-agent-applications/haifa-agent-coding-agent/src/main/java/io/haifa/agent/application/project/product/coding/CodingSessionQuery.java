package io.haifa.agent.application.project.product.coding;

import java.util.Objects;
import java.util.Optional;

public record CodingSessionQuery(Optional<String> text, Optional<CodingSessionCursor> after, int limit) {
    public CodingSessionQuery {
        text = Objects.requireNonNull(text, "text must not be null")
                .map(value -> CodingProductValues.requireText(value, "text", 120));
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    }

    public static CodingSessionQuery firstPage(int limit) {
        return new CodingSessionQuery(Optional.empty(), Optional.empty(), limit);
    }
}
