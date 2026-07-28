package io.haifa.agent.sdk.product;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Trusted immutable product capability and policy declaration. */
public record ProductProfile(
        String schemaVersion,
        ProductId productId,
        ProductVersion productVersion,
        AgentDefinitionId definitionId,
        AgentDefinitionVersion definitionVersion,
        String runProfileId,
        String runProfileVersion,
        String instructions,
        AgentRunBudget budget,
        AgentRunLimits limits,
        Map<ProductCapabilityId, ProductCapabilityRequirement> capabilityRequirements,
        Set<String> allowedTools,
        Set<String> allowedSkills,
        Set<String> allowedExtensions,
        String configurationDigest) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public ProductProfile {
        schemaVersion = ProductValues.text(schemaVersion, "schemaVersion", 32);
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported product profile schema: " + schemaVersion);
        }
        productId = Objects.requireNonNull(productId, "productId must not be null");
        productVersion = Objects.requireNonNull(productVersion, "productVersion must not be null");
        definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
        definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion must not be null");
        runProfileId = ProductValues.text(runProfileId, "runProfileId", 128);
        runProfileVersion = ProductValues.text(runProfileVersion, "runProfileVersion", 64);
        instructions = ProductValues.text(instructions, "instructions", 32_000);
        budget = Objects.requireNonNull(budget, "budget must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        capabilityRequirements =
                Map.copyOf(Objects.requireNonNull(capabilityRequirements, "capabilityRequirements must not be null"));
        capabilityRequirements.forEach((id, requirement) -> {
            if (!id.equals(requirement.capabilityId())) {
                throw new IllegalArgumentException("capability requirement key must match its capabilityId");
            }
        });
        allowedTools = normalized(allowedTools, "allowedTools");
        allowedSkills = normalized(allowedSkills, "allowedSkills");
        allowedExtensions = normalized(allowedExtensions, "allowedExtensions");
        configurationDigest = ProductValues.requireDigest(configurationDigest, "configurationDigest");
        String expected = digest(
                schemaVersion,
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                runProfileId,
                runProfileVersion,
                instructions,
                budget,
                limits,
                capabilityRequirements,
                allowedTools,
                allowedSkills,
                allowedExtensions);
        if (!expected.equals(configurationDigest)) {
            throw new IllegalArgumentException("product profile digest does not match frozen fields");
        }
    }

    public static ProductProfile create(
            ProductId productId,
            ProductVersion productVersion,
            AgentDefinitionId definitionId,
            AgentDefinitionVersion definitionVersion,
            String runProfileId,
            String runProfileVersion,
            String instructions,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Map<ProductCapabilityId, ProductCapabilityRequirement> requirements,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            Set<String> allowedExtensions) {
        Map<ProductCapabilityId, ProductCapabilityRequirement> safeRequirements = Map.copyOf(requirements);
        Set<String> safeTools = normalized(allowedTools, "allowedTools");
        Set<String> safeSkills = normalized(allowedSkills, "allowedSkills");
        Set<String> safeExtensions = normalized(allowedExtensions, "allowedExtensions");
        return new ProductProfile(
                CURRENT_SCHEMA_VERSION,
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                ProductValues.text(runProfileId, "runProfileId", 128),
                ProductValues.text(runProfileVersion, "runProfileVersion", 64),
                ProductValues.text(instructions, "instructions", 32_000),
                budget,
                limits,
                safeRequirements,
                safeTools,
                safeSkills,
                safeExtensions,
                digest(
                        CURRENT_SCHEMA_VERSION,
                        productId,
                        productVersion,
                        definitionId,
                        definitionVersion,
                        runProfileId,
                        runProfileVersion,
                        instructions,
                        budget,
                        limits,
                        safeRequirements,
                        safeTools,
                        safeSkills,
                        safeExtensions));
    }

    public ProductCapabilityRequirement requirement(ProductCapabilityId capabilityId) {
        return capabilityRequirements.getOrDefault(capabilityId, ProductCapabilityRequirement.none(capabilityId));
    }

    private static String digest(
            String schemaVersion,
            ProductId productId,
            ProductVersion productVersion,
            AgentDefinitionId definitionId,
            AgentDefinitionVersion definitionVersion,
            String runProfileId,
            String runProfileVersion,
            String instructions,
            AgentRunBudget budget,
            AgentRunLimits limits,
            Map<ProductCapabilityId, ProductCapabilityRequirement> requirements,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            Set<String> allowedExtensions) {
        StringBuilder canonical = new StringBuilder();
        canonical
                .append(schemaVersion)
                .append('|')
                .append(productId.value())
                .append('|')
                .append(productVersion.value())
                .append('|')
                .append(definitionId.value())
                .append('|')
                .append(definitionVersion)
                .append('|')
                .append(ProductValues.text(runProfileId, "runProfileId", 128))
                .append('|')
                .append(ProductValues.text(runProfileVersion, "runProfileVersion", 64))
                .append('|')
                .append(ProductValues.text(instructions, "instructions", 32_000))
                .append('|')
                .append(budget.maxInputTokens())
                .append(':')
                .append(budget.maxOutputTokens())
                .append(':')
                .append(budget.maxCachedInputTokens())
                .append(':')
                .append(budget.maxToolCalls())
                .append(':')
                .append(budget.maxModelCalls())
                .append(':')
                .append(budget.maxChildRuns())
                .append(':')
                .append(budget.maxCostCurrency())
                .append(':')
                .append(budget.maxCostMinorUnits())
                .append('|')
                .append(limits.maxIterations())
                .append(':')
                .append(limits.maxDepth())
                .append(':')
                .append(limits.maxParallelChildren())
                .append(':')
                .append(limits.maxWallTimeMillis())
                .append(':')
                .append(limits.maxIdleTimeMillis());
        new TreeMap<>(requirements).forEach((id, requirement) -> canonical
                .append('|')
                .append(id.value())
                .append(':')
                .append(requirement.mode())
                .append(':')
                .append(requirement.minimumSuitability())
                .append(':')
                .append(requirement.allowedContributions().stream()
                        .sorted()
                        .map(ProductContributionCoordinate::externalForm)
                        .toList()));
        canonical.append("|tools=").append(allowedTools.stream().sorted().toList());
        canonical.append("|skills=").append(allowedSkills.stream().sorted().toList());
        canonical
                .append("|extensions=")
                .append(allowedExtensions.stream().sorted().toList());
        return ProductValues.digest(canonical.toString());
    }

    private static Set<String> normalized(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        return values.stream()
                .map(value -> ProductValues.text(value, field + " entry", 256))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
