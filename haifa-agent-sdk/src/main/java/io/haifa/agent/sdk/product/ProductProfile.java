package io.haifa.agent.sdk.product;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import java.util.ArrayList;
import java.util.List;
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
        ProductPolicies policies,
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
        policies = Objects.requireNonNull(policies, "policies must not be null");
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
                policies,
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
        return create(
                productId,
                productVersion,
                definitionId,
                definitionVersion,
                runProfileId,
                runProfileVersion,
                instructions,
                budget,
                limits,
                ProductPolicies.safeDefaults(),
                requirements,
                allowedTools,
                allowedSkills,
                allowedExtensions);
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
            ProductPolicies policies,
            Map<ProductCapabilityId, ProductCapabilityRequirement> requirements,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            Set<String> allowedExtensions) {
        Map<ProductCapabilityId, ProductCapabilityRequirement> safeRequirements = Map.copyOf(requirements);
        Set<String> safeTools = normalized(allowedTools, "allowedTools");
        Set<String> safeSkills = normalized(allowedSkills, "allowedSkills");
        Set<String> safeExtensions = normalized(allowedExtensions, "allowedExtensions");
        ProductPolicies safePolicies = Objects.requireNonNull(policies, "policies must not be null");
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
                safePolicies,
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
                        safePolicies,
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
            ProductPolicies policies,
            Map<ProductCapabilityId, ProductCapabilityRequirement> requirements,
            Set<String> allowedTools,
            Set<String> allowedSkills,
            Set<String> allowedExtensions) {
        List<String> fields = new ArrayList<>();
        add(fields, "schema", schemaVersion);
        add(fields, "productId", productId.value());
        add(fields, "productVersion", productVersion.value());
        add(fields, "definitionId", definitionId.value());
        add(fields, "definitionMajor", definitionVersion.major());
        add(fields, "definitionMinor", definitionVersion.minor());
        add(fields, "definitionPatch", definitionVersion.patch());
        add(fields, "runProfileId", ProductValues.text(runProfileId, "runProfileId", 128));
        add(fields, "runProfileVersion", ProductValues.text(runProfileVersion, "runProfileVersion", 64));
        add(fields, "instructions", ProductValues.text(instructions, "instructions", 32_000));
        add(fields, "budget.quotaMode", budget.quotaMode().name());
        add(fields, "budget.maxInputTokens", budget.maxInputTokens());
        add(fields, "budget.maxOutputTokens", budget.maxOutputTokens());
        add(fields, "budget.maxCachedInputTokens", budget.maxCachedInputTokens());
        add(fields, "budget.maxCostCurrency", budget.maxCostCurrency());
        add(fields, "budget.maxCostMinorUnits", budget.maxCostMinorUnits());
        add(fields, "limits.maxIterations", limits.maxIterations());
        add(fields, "limits.maxDepth", limits.maxDepth());
        add(fields, "limits.maxParallelChildren", limits.maxParallelChildren());
        add(fields, "limits.maxWallTimeMillis", limits.maxWallTimeMillis());
        add(fields, "limits.maxIdleTimeMillis", limits.maxIdleTimeMillis());
        add(fields, "limits.maxModelCalls", limits.maxModelCalls());
        add(fields, "limits.maxToolCalls", limits.maxToolCalls());
        add(fields, "limits.maxChildRuns", limits.maxChildRuns());
        add(fields, "memory.manualReviewRequired", policies.memory().manualReviewRequired());
        add(fields, "memory.maxCandidateContentChars", policies.memory().maxCandidateContentChars());
        add(fields, "memory.maxQueryLimit", policies.memory().maxQueryLimit());
        add(fields, "artifact.maxArtifactBytes", policies.artifact().maxArtifactBytes());
        add(fields, "artifact.maxArtifactsPerRun", policies.artifact().maxArtifactsPerRun());
        add(fields, "artifact.maxArtifactBytesPerRun", policies.artifact().maxArtifactBytesPerRun());
        add(fields, "artifact.rangeSupported", policies.artifact().rangeSupported());
        add(fields, "artifact.localSoftLimitBytes", policies.artifact().localSoftLimitBytes());
        add(fields, "artifact.localHardLimitBytes", policies.artifact().localHardLimitBytes());
        add(fields, "artifact.requiredCompletionGate", policies.artifact().requiredCompletionGate());
        addSorted(fields, "artifact.allowedMediaType", policies.artifact().allowedMediaTypes());
        add(fields, "execution.enabled", policies.execution().enabled());
        add(fields, "execution.hostAccessAllowed", policies.execution().hostAccessAllowed());
        add(fields, "execution.externalNetworkAllowed", policies.execution().externalNetworkAllowed());
        add(fields, "execution.maxParallelExecutions", policies.execution().maxParallelExecutions());
        add(fields, "execution.maxExecutionMillis", policies.execution().maxExecutionMillis());
        new TreeMap<>(requirements).forEach((id, requirement) -> {
            add(fields, "capability.id", id.value());
            add(fields, "capability.mode", requirement.mode().name());
            add(fields, "capability.minimumSuitability", requirement.minimumSuitability());
            requirement.allowedContributions().stream().sorted().forEach(coordinate -> {
                add(fields, "capability.providerId", coordinate.providerId());
                add(fields, "capability.version", coordinate.version());
            });
        });
        addSorted(fields, "allowedTool", allowedTools);
        addSorted(fields, "allowedSkill", allowedSkills);
        addSorted(fields, "allowedExtension", allowedExtensions);
        return SdkConfigurationDigest.sha256(fields.toArray(String[]::new));
    }

    private static void add(List<String> fields, String name, Object value) {
        fields.add(name);
        fields.add(String.valueOf(value));
    }

    private static void addSorted(List<String> fields, String name, Set<String> values) {
        add(fields, name + ".count", values.size());
        values.stream().sorted().forEach(value -> add(fields, name, value));
    }

    private static Set<String> normalized(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        return values.stream()
                .map(value -> ProductValues.text(value, field + " entry", 256))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
