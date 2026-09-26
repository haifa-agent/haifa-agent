package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentCapabilityRequirement;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime view of one Agent definition.
 *
 * <p>{@code description} is shown to a parent model when this definition is an allowed child agent.
 * {@code childRunProfileId} names the run profile a child run of this definition uses; when absent the child
 * inherits the frozen model, budget and limits of its parent run.
 */
public record ResolvedDefinition(
        AgentDefinitionId id,
        AgentDefinitionVersion version,
        Set<String> allowedTools,
        Set<String> allowedSkills,
        Set<AgentDefinitionId> allowedChildAgents,
        String instruction,
        List<AgentCapabilityRequirement> capabilityRequirements,
        String description,
        Optional<String> childRunProfileId) {
    public ResolvedDefinition(
            AgentDefinitionId id,
            AgentDefinitionVersion version,
            Set<String> allowedTools,
            Set<AgentDefinitionId> allowedChildAgents,
            String instruction) {
        this(id, version, allowedTools, Set.of(), allowedChildAgents, instruction, List.of());
    }

    public ResolvedDefinition(
            AgentDefinitionId id,
            AgentDefinitionVersion version,
            Set<String> allowedTools,
            Set<AgentDefinitionId> allowedChildAgents,
            String instruction,
            List<AgentCapabilityRequirement> capabilityRequirements) {
        this(id, version, allowedTools, Set.of(), allowedChildAgents, instruction, capabilityRequirements);
    }

    public ResolvedDefinition(
            AgentDefinitionId id,
            AgentDefinitionVersion version,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            Set<AgentDefinitionId> allowedChildAgents,
            String instruction,
            List<AgentCapabilityRequirement> capabilityRequirements) {
        this(
                id,
                version,
                allowedTools,
                allowedSkills,
                allowedChildAgents,
                instruction,
                capabilityRequirements,
                "",
                Optional.empty());
    }

    public ResolvedDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools must not be null"));
        allowedSkills = Set.copyOf(Objects.requireNonNull(allowedSkills, "allowedSkills must not be null"));
        allowedChildAgents =
                Set.copyOf(Objects.requireNonNull(allowedChildAgents, "allowedChildAgents must not be null"));
        instruction = Objects.requireNonNull(instruction, "instruction must not be null")
                .trim();
        if (instruction.isEmpty()) throw new IllegalArgumentException("instruction must not be blank");
        capabilityRequirements =
                List.copyOf(Objects.requireNonNull(capabilityRequirements, "capabilityRequirements must not be null"));
        description = Objects.requireNonNull(description, "description must not be null")
                .trim();
        childRunProfileId = Objects.requireNonNull(childRunProfileId, "childRunProfileId must not be null")
                .map(String::trim);
        if (childRunProfileId.filter(String::isEmpty).isPresent()) {
            throw new IllegalArgumentException("childRunProfileId must not be blank");
        }
    }

    /**
     * Returns this definition narrowed to what a child run may actually receive: the given Tool and Skill
     * subsets and no child agents of its own, so delegation depth stays at one.
     */
    public ResolvedDefinition narrowedForChild(Set<String> tools, Set<String> skills) {
        return new ResolvedDefinition(
                id,
                version,
                tools,
                skills,
                Set.of(),
                instruction,
                capabilityRequirements,
                description,
                childRunProfileId);
    }
}
