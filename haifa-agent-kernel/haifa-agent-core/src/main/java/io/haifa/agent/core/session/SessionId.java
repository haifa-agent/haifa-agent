package io.haifa.agent.core.session;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed Agent session identifier. */
public record SessionId(String value) implements Identifier {

    public SessionId {
        value = Identifiers.requireValid(value, "sessionId");
    }

    public static SessionId create() {
        return new SessionId(Identifiers.randomValue());
    }
}
