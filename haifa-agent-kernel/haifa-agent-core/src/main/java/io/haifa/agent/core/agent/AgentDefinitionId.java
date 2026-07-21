package io.haifa.agent.core.agent;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed identifier of a versioned Agent definition. */
public record AgentDefinitionId(String value) implements Identifier {
    public AgentDefinitionId {
        value = Identifiers.requireValid(value, "agentDefinitionId");
    }
}
