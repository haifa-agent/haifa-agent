package io.haifa.agent.core.agent;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, versioned definition of an Agent role and its required capabilities. */
public record AgentDefinition(
        AgentDefinitionId id,
        AgentDefinitionVersion version,
        String name,
        String description,
        AgentType type,
        String instructionTemplateRef,
        String modelProfileRef,
        Set<String> allowedToolNames,
        Set<String> allowedSkillIds,
        Set<String> allowedChildAgentTypes,
        List<AgentCapabilityRequirement> capabilityRequirements,
        AgentOutputContractRef outputContract,
        Map<String, Object> metadata) {

    public AgentDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        name = requireText(name, "name");
        description = requireText(description, "description");
        type = Objects.requireNonNull(type, "type must not be null");
        instructionTemplateRef = requireText(instructionTemplateRef, "instructionTemplateRef");
        modelProfileRef = requireText(modelProfileRef, "modelProfileRef");
        allowedToolNames = normalizedSet(allowedToolNames, "allowedToolNames");
        allowedSkillIds = normalizedSet(allowedSkillIds, "allowedSkillIds");
        allowedChildAgentTypes = normalizedSet(allowedChildAgentTypes, "allowedChildAgentTypes");
        capabilityRequirements =
                List.copyOf(Objects.requireNonNull(capabilityRequirements, "capabilityRequirements must not be null"));
        outputContract = Objects.requireNonNull(outputContract, "outputContract must not be null");
        metadata = immutableMap(metadata, "metadata");
    }

    private static Set<String> normalizedSet(Set<String> source, String field) {
        Objects.requireNonNull(source, field + " must not be null");
        return source.stream()
                .map(value -> requireText(value, field + " value"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
