package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import java.util.Objects;
import java.util.Set;

public record ResolvedDefinition(
        AgentDefinitionId id,
        AgentDefinitionVersion version,
        Set<String> allowedTools,
        Set<AgentDefinitionId> allowedChildAgents,
        String instruction) {
    public ResolvedDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools must not be null"));
        allowedChildAgents =
                Set.copyOf(Objects.requireNonNull(allowedChildAgents, "allowedChildAgents must not be null"));
        instruction = Objects.requireNonNull(instruction, "instruction must not be null")
                .trim();
        if (instruction.isEmpty()) throw new IllegalArgumentException("instruction must not be blank");
    }
}
