package io.haifa.agent.core.message;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed append-only message identifier. */
public record AgentMessageId(String value) implements Identifier {
    public AgentMessageId {
        value = Identifiers.requireValid(value, "agentMessageId");
    }
}
