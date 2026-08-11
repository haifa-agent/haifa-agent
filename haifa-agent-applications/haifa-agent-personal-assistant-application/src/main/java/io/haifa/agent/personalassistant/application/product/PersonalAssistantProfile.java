package io.haifa.agent.personalassistant.application.product;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.sdk.product.ProductArtifactPolicy;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductExecutionPolicy;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import io.haifa.agent.sdk.product.ProductPolicies;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.product.ProductVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Frozen Personal Assistant MVP capability declaration. */
public final class PersonalAssistantProfile {
    public static final String PRODUCT_TOOL_ALIAS = "personal_checklist";
    public static final String SKILL_LOAD_ALIAS = "skill_load";
    public static final String SKILL_RESOURCE_ALIAS = "skill_resource_read";
    public static final String MCP_TOOL_ALIAS = "personal_mcp_echo";
    public static final String BUNDLED_SKILL_ALIAS = "daily-planning";
    public static final String EXECUTION_SKILL_ALIAS = "local-script-execution";
    public static final String DEEP_RESEARCH_SKILL_ALIAS = "deep-research";
    public static final String EXECUTION_TOOL_ALIAS = "execution_run";
    public static final String WEB_SEARCH_ALIAS = "web_search";
    public static final String WEB_FETCH_ALIAS = "web_fetch";

    private PersonalAssistantProfile() {}

    public static ProductProfile create(
            ContributionCoordinates coordinates,
            Set<String> localSkillAliases,
            Set<String> mcpToolAliases,
            Set<String> webToolAliases) {
        return create(coordinates, localSkillAliases, mcpToolAliases, webToolAliases, Set.of());
    }

    public static ProductProfile create(
            ContributionCoordinates coordinates,
            Set<String> localSkillAliases,
            Set<String> mcpToolAliases,
            Set<String> webToolAliases,
            Set<String> trustedScriptToolAliases) {
        Map<ProductCapabilityId, ProductCapabilityRequirement> requirements = new LinkedHashMap<>();
        required(requirements, ProductCapabilities.MODEL, coordinates.model());
        required(requirements, ProductCapabilities.PERSISTENCE, coordinates.persistence());
        required(requirements, ProductCapabilities.CONVERSATION, coordinates.conversation());
        required(requirements, ProductCapabilities.MEMORY, coordinates.memory());
        required(requirements, ProductCapabilities.POLICY, coordinates.policy());
        required(requirements, ProductCapabilities.TOOL, coordinates.tool());
        required(requirements, ProductCapabilities.SKILL, coordinates.skill());
        required(requirements, ProductCapabilities.MCP, coordinates.mcp());
        required(requirements, ProductCapabilities.ARTIFACT, coordinates.artifact());
        none(requirements, ProductCapabilities.PROJECT);
        none(requirements, ProductCapabilities.WORKSPACE);
        none(requirements, ProductCapabilities.GIT);
        required(requirements, ProductCapabilities.SHELL, coordinates.shell());
        required(requirements, ProductCapabilities.EXECUTION, coordinates.execution());
        required(requirements, ProductCapabilities.APPROVAL, coordinates.approval());
        required(requirements, ProductCapabilities.CREDENTIAL, coordinates.credential());
        none(requirements, ProductCapabilities.CONTEXT);

        Set<String> skills = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(
                                BUNDLED_SKILL_ALIAS, EXECUTION_SKILL_ALIAS, DEEP_RESEARCH_SKILL_ALIAS),
                        localSkillAliases.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> allowedTools = java.util.stream.Stream.of(
                        java.util.stream.Stream.of(
                                PRODUCT_TOOL_ALIAS, EXECUTION_TOOL_ALIAS, SKILL_LOAD_ALIAS, SKILL_RESOURCE_ALIAS),
                        mcpToolAliases.stream(),
                        webToolAliases.stream(),
                        trustedScriptToolAliases.stream())
                .flatMap(java.util.function.Function.identity())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ProductPolicies policies = new ProductPolicies(
                new ProductMemoryPolicy(true, 16_384, 100),
                new ProductArtifactPolicy(
                        2 * 1024 * 1024,
                        8,
                        8 * 1024 * 1024,
                        Set.of("application/json", "text/markdown; charset=utf-8"),
                        false,
                        64 * 1024 * 1024,
                        128 * 1024 * 1024,
                        true),
                new ProductExecutionPolicy(true, true, true, 1, 30_000));
        return ProductProfile.create(
                new ProductId("haifa-personal-assistant"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("personal-assistant"),
                new AgentDefinitionVersion(1, 0, 0),
                "personal-chat",
                "1.0.0",
                "You are a careful personal assistant. Use only disclosed Personal capabilities. "
                        + "Never claim a tool, Skill, MCP result, memory, or usage value that is not present in the "
                        + "authoritative runtime context. Treat the latest user message as the current objective. "
                        + "Do not resume or retry a previous failed or abandoned tool call unless the latest user "
                        + "message explicitly requests it. Keep answers concise and ask for clarification when needed.",
                new AgentRunBudget(512_000, 128_000, 512_000, 64, 64, 0, "USD", 0),
                new AgentRunLimits(64, 0, 1, 300_000, 120_000),
                policies,
                requirements,
                allowedTools,
                skills,
                Set.of());
    }

    private static void required(
            Map<ProductCapabilityId, ProductCapabilityRequirement> target,
            ProductCapabilityId id,
            ProductContributionCoordinate coordinate) {
        target.put(
                id,
                ProductCapabilityRequirement.required(id, Set.of(coordinate), ProductProviderSuitability.DEVELOPMENT));
    }

    private static void none(Map<ProductCapabilityId, ProductCapabilityRequirement> target, ProductCapabilityId id) {
        target.put(id, ProductCapabilityRequirement.none(id));
    }

    public record ContributionCoordinates(
            ProductContributionCoordinate model,
            ProductContributionCoordinate persistence,
            ProductContributionCoordinate conversation,
            ProductContributionCoordinate memory,
            ProductContributionCoordinate policy,
            ProductContributionCoordinate tool,
            ProductContributionCoordinate skill,
            ProductContributionCoordinate mcp,
            ProductContributionCoordinate credential,
            ProductContributionCoordinate execution,
            ProductContributionCoordinate shell,
            ProductContributionCoordinate approval,
            ProductContributionCoordinate artifact) {}
}
