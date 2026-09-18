package io.haifa.agent.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, transport-neutral view of one authoritative plan item. */
public record TodoItemView(
        String id,
        String title,
        String description,
        String priority,
        List<String> dependencies,
        String status,
        Optional<String> completionSummary,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt) {
    public TodoItemView {
        id = requireText(id, "id");
        title = requireText(title, "title");
        description = requireText(description, "description");
        priority = requireText(priority, "priority");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
        status = requireText(status, "status");
        completionSummary = Objects.requireNonNull(completionSummary, "completionSummary must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    private static String requireText(String value, String field) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return checked;
    }
}
