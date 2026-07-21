package io.haifa.agent.context.item;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record ContextItemId(String value) implements Identifier {
    public ContextItemId {
        value = Identifiers.requireValid(value, "contextItemId");
    }
}
