package io.haifa.agent.core.tool;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Runtime-owned execution deduplication key; never doubles as a provider correlation identifier. */
public record RuntimeIdempotencyKey(String value) implements Identifier {
    public RuntimeIdempotencyKey {
        value = Identifiers.requireValid(value, "runtimeIdempotencyKey");
    }
}
