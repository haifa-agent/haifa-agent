package io.haifa.agent.core.run;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed Agent run identifier. */
public record AgentRunId(String value) implements Identifier {
    public AgentRunId {
        value = Identifiers.requireValid(value, "agentRunId");
    }
}
