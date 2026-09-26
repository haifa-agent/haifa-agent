package io.haifa.agent.sdk.product;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.Objects;
import java.util.Set;

/**
 * Trusted immutable product selection and defaults.
 *
 * <p>Sub-system governance (Memory, Artifact, Execution) lives with the assembled component; the
 * Runtime Configuration Snapshot remains the only authoritative frozen execution fact.
 */
public record ProductProfile(
        ProductId productId,
        ProductVersion productVersion,
        AgentDefinitionId definitionId,
        AgentDefinitionVersion definitionVersion,
        String instructions,
        ProductRunProfileRef defaultRunProfile,
        AgentRunBudget budget,
        AgentRunLimits limits,
        Set<String> allowedTools,
        Set<String> allowedSkills,
        Set<String> allowedChildAgents) {

    /** A profile whose runs cannot delegate to child agents. */
    public ProductProfile(
            ProductId productId,
            ProductVersion productVersion,
            AgentDefinitionId definitionId,
            AgentDefinitionVersion definitionVersion,
            String instructions,
            ProductRunProfileRef defaultRunProfile,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Set<String> allowedTools,
            Set<String> allowedSkills) {
        this(
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                instructions,
                defaultRunProfile,
                budget,
                limits,
                allowedTools,
                allowedSkills,
                Set.of());
    }

    public ProductProfile {
        productId = Objects.requireNonNull(productId, "productId must not be null");
        productVersion = Objects.requireNonNull(productVersion, "productVersion must not be null");
        definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null");
        instructions = ProductValues.text(instructions, "instructions", 32_000);
        defaultRunProfile = Objects.requireNonNull(defaultRunProfile, "defaultRunProfile must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        allowedTools = normalized(allowedTools, "allowedTools");
        allowedSkills = normalized(allowedSkills, "allowedSkills");
        allowedChildAgents = normalized(allowedChildAgents, "allowedChildAgents");
    }

    /** Returns this profile allowing its runs to delegate to the registered child agents with these ids. */
    public ProductProfile withAllowedChildAgents(Set<String> childAgentIds) {
        return new ProductProfile(
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                instructions,
                defaultRunProfile,
                budget,
                limits,
                allowedTools,
                allowedSkills,
                childAgentIds);
    }

    public static ProductProfile create(
            ProductId productId,
            ProductVersion productVersion,
            AgentDefinitionId definitionId,
            AgentDefinitionVersion definitionVersion,
            String instructions,
            ProductRunProfileRef defaultRunProfile,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Set<String> allowedTools,
            Set<String> allowedSkills) {
        return new ProductProfile(
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                instructions,
                defaultRunProfile,
                budget,
                limits,
                allowedTools,
                allowedSkills);
    }

    private static Set<String> normalized(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        return values.stream()
                .map(value -> ProductValues.text(value, field + " entry", 256))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
