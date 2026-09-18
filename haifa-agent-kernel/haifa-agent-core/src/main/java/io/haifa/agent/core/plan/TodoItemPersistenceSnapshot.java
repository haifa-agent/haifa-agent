package io.haifa.agent.core.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, versioned persistence contract for one plan item. */
public record TodoItemPersistenceSnapshot(
        String schemaVersion,
        TodoItemId id,
        String title,
        String description,
        String priority,
        List<TodoItemId> dependencies,
        String status,
        String completionSummary,
        Instant startedAt,
        Instant completedAt) {

    public TodoItemPersistenceSnapshot {
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
    }
}
