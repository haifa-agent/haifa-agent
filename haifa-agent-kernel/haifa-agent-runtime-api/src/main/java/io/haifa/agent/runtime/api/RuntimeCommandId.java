package io.haifa.agent.runtime.api;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed identifier for an externally submitted Runtime command. */
public record RuntimeCommandId(String value) implements Identifier {
    public RuntimeCommandId {
        value = Identifiers.requireValid(value, "runtimeCommandId");
    }
}
