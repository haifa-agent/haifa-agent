package io.haifa.agent.core.plan;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed plan item identifier. */
public record TodoItemId(String value) implements Identifier {
    public TodoItemId {
        value = Identifiers.requireValid(value, "todoItemId");
    }
}
