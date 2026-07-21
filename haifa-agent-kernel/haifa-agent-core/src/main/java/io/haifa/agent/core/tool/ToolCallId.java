package io.haifa.agent.core.tool;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed tool invocation identifier. */
public record ToolCallId(String value) implements Identifier {
    public ToolCallId {
        value = Identifiers.requireValid(value, "toolCallId");
    }
}
