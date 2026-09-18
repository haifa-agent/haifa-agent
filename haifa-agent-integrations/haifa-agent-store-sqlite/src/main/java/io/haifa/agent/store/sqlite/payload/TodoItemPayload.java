package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoItemPersistenceSnapshot;
import java.time.Instant;
import java.util.List;

public record TodoItemPayload(
        String schemaVersion,
        String id,
        String title,
        String description,
        String priority,
        List<String> dependencies,
        String status,
        String completionSummary,
        Long startedAt,
        Long completedAt) {
    public static TodoItemPayload from(TodoItemPersistenceSnapshot value) {
        return new TodoItemPayload(
                value.schemaVersion(),
                value.id().value(),
                value.title(),
                value.description(),
                value.priority(),
                value.dependencies().stream().map(TodoItemId::value).toList(),
                value.status(),
                value.completionSummary(),
                value.startedAt() == null ? null : value.startedAt().toEpochMilli(),
                value.completedAt() == null ? null : value.completedAt().toEpochMilli());
    }

    public TodoItemPersistenceSnapshot toSnapshot() {
        return new TodoItemPersistenceSnapshot(
                schemaVersion,
                new TodoItemId(id),
                title,
                description,
                priority,
                dependencies.stream().map(TodoItemId::new).toList(),
                status,
                completionSummary,
                startedAt == null ? null : Instant.ofEpochMilli(startedAt),
                completedAt == null ? null : Instant.ofEpochMilli(completedAt));
    }
}
