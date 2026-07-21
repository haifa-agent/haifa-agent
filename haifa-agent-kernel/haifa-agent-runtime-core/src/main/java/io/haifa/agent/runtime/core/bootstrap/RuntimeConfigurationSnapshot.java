package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import java.util.Objects;
import java.util.Set;

/** Immutable materialized configuration whose content hash is referenced by the Core run aggregate. */
public record RuntimeConfigurationSnapshot(
        RunConfigurationSnapshotRef reference,
        AgentDefinitionId definitionId,
        AgentDefinitionVersion definitionVersion,
        String profileId,
        String profileVersion,
        AgentRunType runType,
        AgentRunBudget budget,
        AgentRunLimits limits,
        Set<String> allowedTools,
        Set<AgentDefinitionId> allowedChildAgents,
        String agentInstruction,
        RuntimeOverrides overrides) {
    public RuntimeConfigurationSnapshot {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null");
        profileId = requireText(profileId, "profileId");
        profileVersion = requireText(profileVersion, "profileVersion");
        runType = Objects.requireNonNull(runType, "runType must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools must not be null"));
        allowedChildAgents =
                Set.copyOf(Objects.requireNonNull(allowedChildAgents, "allowedChildAgents must not be null"));
        agentInstruction = requireText(agentInstruction, "agentInstruction");
        overrides = Objects.requireNonNull(overrides, "overrides must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
