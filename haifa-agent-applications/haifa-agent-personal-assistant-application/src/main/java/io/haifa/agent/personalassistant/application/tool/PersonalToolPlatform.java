package io.haifa.agent.personalassistant.application.tool;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.core.tool.ExecutionToolSchemaValidator;
import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillToolContributions;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.util.List;
import java.util.Set;

/** Freezes product, Skill, and MCP Tools into one catalog and one Runtime Tool pipeline. */
public record PersonalToolPlatform(
        ToolPlatformContribution tool,
        SkillPlatformContribution skill,
        io.haifa.agent.sdk.contribution.McpToolCatalogContribution mcp,
        Set<String> trustedScriptToolAliases) {
    public static final ProductContributionCoordinate TOOL_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-tools", "1.0.0");
    public static final ProductContributionCoordinate SKILL_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-skills", "1.0.0");
    public static final ProductContributionCoordinate MCP_COORDINATE =
            new ProductContributionCoordinate("haifa-personal-local-mcp", "1.0.0");

    public static PersonalToolPlatform create(
            SdkPersistenceContribution persistence,
            PersonalSkillPlatform skills,
            PersonalMcpPlatform mcp,
            PersonalWebPlatform web,
            PersonalExecutionPlatform execution,
            TimeProvider time) {
        var builder = new ToolCatalogBuilder();
        var checklist = new PersonalChecklistTool();
        builder.register(
                PersonalChecklistTool.ALIAS, PersonalChecklistTool.definition(), "personal-checklist-v1", checklist);
        builder.register(
                io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory.ALIAS,
                execution.definition(),
                "personal-execution-v2",
                execution.provider());
        PersonalTrustedFinanceTools.Prepared trusted = PersonalTrustedFinanceTools.prepare(skills, execution);
        trusted.provider().ifPresent(provider -> trusted.entries()
                .forEach(item -> builder.register(
                        item.alias(), item.spec().definition(), item.providerBindingReference(), provider)));
        web.contributions()
                .forEach(item -> builder.register(
                        item.alias(), item.definition(), item.providerBindingReference(), item.provider()));
        List<SkillToolCatalogContribution> skillTools =
                SkillToolContributions.create(persistence, skills.contentLoader(), time);
        skillTools.forEach(item ->
                builder.register(item.alias(), item.definition(), item.providerBindingReference(), item.provider()));
        List<McpToolCatalogContribution> mcpTools = mcp.contributions();
        mcpTools.forEach(item ->
                builder.register(item.alias(), item.definition(), item.providerBindingReference(), item.provider()));
        var catalog = builder.freeze();
        var trust = PersonalTrustedFinanceTools.freezeTrust(skills, trusted, catalog);

        var tool = new ToolPlatformContribution(
                metadata(
                        TOOL_COORDINATE,
                        ProductCapabilities.TOOL,
                        "sha256:" + catalog.snapshot().digest(),
                        "Personal unified Tool catalog"),
                catalog,
                new DefaultToolInvoker(catalog),
                new ExecutionToolSchemaValidator(new JsonSchema202012Validator()));
        var skill = new SkillPlatformContribution(
                metadata(
                        SKILL_COORDINATE,
                        ProductCapabilities.SKILL,
                        skills.catalog().snapshot().digest().value(),
                        "Personal bundled and trusted local Skills"),
                skills.catalog(),
                skills.contentLoader(),
                trust);
        Set<String> aliases = mcpTools.stream()
                .map(item -> item.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var mcpContribution = new io.haifa.agent.sdk.contribution.McpToolCatalogContribution(
                metadata(
                        MCP_COORDINATE,
                        ProductCapabilities.MCP,
                        SdkConfigurationDigest.sha256(aliases.stream().sorted().toArray(String[]::new)),
                        "Personal explicit loopback MCP allowlist"),
                aliases);
        return new PersonalToolPlatform(tool, skill, mcpContribution, trusted.aliases());
    }

    private static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate,
            io.haifa.agent.sdk.product.ProductCapabilityId capability,
            String digest,
            String description) {
        return new SdkContributionMetadata(
                coordinate, capability, digest, ProductProviderSuitability.PRODUCTION, description);
    }
}
