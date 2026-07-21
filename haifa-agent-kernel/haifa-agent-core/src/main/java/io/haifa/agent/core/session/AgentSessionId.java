package io.haifa.agent.core.session;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed Agent session identifier. */
public record AgentSessionId(String value) implements Identifier {
    public AgentSessionId {
        value = Identifiers.requireValid(value, "agentSessionId");
    }
}
