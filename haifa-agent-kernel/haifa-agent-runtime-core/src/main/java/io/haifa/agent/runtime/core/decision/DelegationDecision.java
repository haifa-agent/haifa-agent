package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.agent.AgentDefinitionId;
import java.util.Objects;

public record DelegationDecision(AgentDefinitionId childDefinitionId, String objective) implements AgentDecision {
    public DelegationDecision {
        childDefinitionId = Objects.requireNonNull(childDefinitionId, "childDefinitionId must not be null");
        objective =
                Objects.requireNonNull(objective, "objective must not be null").trim();
        if (objective.isEmpty()) throw new IllegalArgumentException("objective must not be blank");
    }
}
