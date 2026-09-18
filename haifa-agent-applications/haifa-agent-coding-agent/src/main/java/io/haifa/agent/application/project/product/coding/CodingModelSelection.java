package io.haifa.agent.application.project.product.coding;

import java.util.Objects;

/** Current Session model preference enriched with safe catalog data. */
public record CodingModelSelection(CodingModelOption model, long revision, boolean available) {
    public CodingModelSelection {
        model = Objects.requireNonNull(model, "model must not be null");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
