package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;

/** Progress state broken down into completed, active, and blocked items. */
public record SemanticProgress(
        List<SemanticSummaryItem> completed,
        List<SemanticSummaryItem> active,
        List<SemanticSummaryItem> blocked) {
    public SemanticProgress {
        completed = List.copyOf(Objects.requireNonNull(completed, "completed must not be null"));
        active = List.copyOf(Objects.requireNonNull(active, "active must not be null"));
        blocked = List.copyOf(Objects.requireNonNull(blocked, "blocked must not be null"));
    }

    public static SemanticProgress empty() {
        return new SemanticProgress(List.of(), List.of(), List.of());
    }
}
