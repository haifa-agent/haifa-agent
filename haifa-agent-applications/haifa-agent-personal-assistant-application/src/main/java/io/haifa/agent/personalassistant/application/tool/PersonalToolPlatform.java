package io.haifa.agent.personalassistant.application.tool;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.core.tool.ExecutionToolSchemaValidator;
import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillToolContributions;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.util.List;
import java.util.Set;

/** Freezes product, Skill, and MCP Tools into one catalog and one Runtime Tool pipeline. */
public record PersonalToolPlatform(
        ToolPlatformContribution tool, SkillPlatformContribution skill, Set<String> trustedScriptToolAliases) {

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
        var trust = new SkillTrustSnapshot(
                skills.trustManifest().digest(), skills.packageTrust().packageReviewGrants(), List.of());

        var tool = new ToolPlatformContribution(
                catalog,
                new DefaultToolInvoker(catalog),
                new ExecutionToolSchemaValidator(new JsonSchema202012Validator()));
        var skill = new SkillPlatformContribution(skills.catalog(), skills.contentLoader(), trust);
        return new PersonalToolPlatform(tool, skill, Set.of());
    }
}
