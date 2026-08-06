package io.haifa.agent.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, caller-scoped view of an authoritative Runtime plan. */
public record AgentPlanView(
        String id,
        String runId,
        String objective,
        List<TodoItemView> items,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    public AgentPlanView {
        id = requireText(id, "id");
        runId = requireText(runId, "runId");
        objective = requireText(objective, "objective");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }

    private static String requireText(String value, String field) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return checked;
    }
}
