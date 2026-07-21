package io.haifa.agent.application.project.admin;

import java.util.List;
import java.util.Objects;

public record AdminPage<T>(List<T> items, int offset, int limit, boolean hasMore) {
    public AdminPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    }
}
