package io.haifa.agent.core.plan;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed plan identifier. */
public record AgentPlanId(String value) implements Identifier {
    public AgentPlanId {
        value = Identifiers.requireValid(value, "agentPlanId");
    }
}
