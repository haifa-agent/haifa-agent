package io.haifa.agent.core.agent;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed Agent identifier. */
public record AgentId(String value) implements Identifier {

    public AgentId {
        value = Identifiers.requireValid(value, "agentId");
    }

    public static AgentId create() {
        return new AgentId(Identifiers.randomValue());
    }
}
