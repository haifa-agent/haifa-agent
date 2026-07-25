package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.plan.TodoItemPersistenceSnapshot;
import java.util.List;

public record PlanItemsPayload(List<TodoItemPayload> items) {
    public static PlanItemsPayload from(List<TodoItemPersistenceSnapshot> values) {
        return new PlanItemsPayload(values.stream().map(TodoItemPayload::from).toList());
    }

    public List<TodoItemPersistenceSnapshot> toSnapshots() {
        return items.stream().map(TodoItemPayload::toSnapshot).toList();
    }
}
