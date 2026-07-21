package io.haifa.agent.contract.common;

import java.util.List;
import java.util.Objects;

/** Transport-neutral page response with zero-based page numbering. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {

    public PageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (totalElements < items.size()) {
            throw new IllegalArgumentException("totalElements must not be smaller than the current page");
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("items must not exceed the requested page size");
        }
    }

    public long totalPages() {
        return totalElements == 0 ? 0 : ((totalElements - 1) / size) + 1;
    }
}
