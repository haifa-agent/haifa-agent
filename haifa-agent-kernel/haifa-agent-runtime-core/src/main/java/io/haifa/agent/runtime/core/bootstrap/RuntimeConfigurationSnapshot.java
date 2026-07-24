package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.List;
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
        List<FrozenToolBinding> toolBindings,
        List<FrozenSkillBinding> skillBindings,
        SkillContentDigest skillCatalogDigest,
        String skillResolutionPolicyRef,
        Set<AgentDefinitionId> allowedChildAgents,
        String agentInstruction,
        RuntimeOverrides overrides,
        List<EffectiveCapability> capabilities,
        ResolvedModelSnapshot model) {
    public RuntimeConfigurationSnapshot {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null");
        profileId = requireText(profileId, "profileId");
        profileVersion = requireText(profileVersion, "profileVersion");
        runType = Objects.requireNonNull(runType, "runType must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        toolBindings = List.copyOf(Objects.requireNonNull(toolBindings, "toolBindings must not be null"));
        long distinctAliases =
                toolBindings.stream().map(FrozenToolBinding::alias).distinct().count();
        if (distinctAliases != toolBindings.size()) {
            throw new IllegalArgumentException("frozen tool aliases must be unique");
        }
        skillBindings = List.copyOf(Objects.requireNonNull(skillBindings, "skillBindings must not be null"));
        long distinctSkillAliases =
                skillBindings.stream().map(FrozenSkillBinding::alias).distinct().count();
        if (distinctSkillAliases != skillBindings.size()) {
            throw new IllegalArgumentException("frozen skill aliases must be unique");
        }
        skillCatalogDigest = Objects.requireNonNull(skillCatalogDigest, "skillCatalogDigest must not be null");
        skillResolutionPolicyRef = requireText(skillResolutionPolicyRef, "skillResolutionPolicyRef");
        allowedChildAgents =
                Set.copyOf(Objects.requireNonNull(allowedChildAgents, "allowedChildAgents must not be null"));
        agentInstruction = requireText(agentInstruction, "agentInstruction");
        overrides = Objects.requireNonNull(overrides, "overrides must not be null");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        model = Objects.requireNonNull(model, "model must not be null");
    }

    public Set<String> allowedTools() {
        return toolBindings.stream()
                .map(binding -> binding.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> allowedSkills() {
        return skillBindings.stream()
                .map(binding -> binding.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
