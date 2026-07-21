package io.haifa.agent.core.step;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed persisted step identifier. */
public record AgentStepId(String value) implements Identifier {
    public AgentStepId {
        value = Identifiers.requireValid(value, "agentStepId");
    }
}
