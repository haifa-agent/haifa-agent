package io.haifa.agent.context.prompt;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

public record PromptComponentId(String value) implements Identifier {
    public PromptComponentId {
        value = Identifiers.requireValid(value, "promptComponentId");
    }
}
