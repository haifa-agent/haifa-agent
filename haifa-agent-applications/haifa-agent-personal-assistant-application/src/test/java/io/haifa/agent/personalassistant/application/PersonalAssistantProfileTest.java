package io.haifa.agent.personalassistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolSchemaValidator;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityMode;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalAssistantProfileTest {
    @Test
    void conversationProfileExcludesMissionOnlyDeepResearchSkill() {
        ProductContributionCoordinate coordinate = new ProductContributionCoordinate("test", "1");
        var profile = PersonalAssistantProfile.create(
                new PersonalAssistantProfile.ContributionCoordinates(
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate),
                Set.of(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS, "user-skill"),
                Set.of(),
                Set.of());

        assertThat(profile.allowedSkills())
                .contains(PersonalAssistantProfile.BUNDLED_SKILL_ALIAS, "user-skill")
                .doesNotContain(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS);
    }

    @Test
    void retriesOnlyExplicitTransientToolFailures() {
        assertThat(PersonalAssistantAssembler.isTransientToolFailure(new ToolInvocationException(
                        "MCP_CALL_DEADLINE_EXCEEDED", ToolDispatchState.OUTCOME_UNKNOWN, "deadline")))
                .isTrue();
        assertThat(PersonalAssistantAssembler.isTransientToolFailure(new ToolInvocationException(
                        "MCP_CALL_OUTCOME_UNKNOWN", ToolDispatchState.OUTCOME_UNKNOWN, "unknown")))
                .isTrue();
        assertThat(PersonalAssistantAssembler.isTransientToolFailure(new ToolInvocationException(
                        "MCP_SESSION_INVALID", ToolDispatchState.NOT_DISPATCHED, "session")))
                .isTrue();
        assertThat(PersonalAssistantAssembler.isTransientToolFailure(new ToolInvocationException(
                        "MCP_AUTH_FLOW_UNSUPPORTED", ToolDispatchState.NOT_DISPATCHED, "auth")))
                .isFalse();
        assertThat(PersonalAssistantAssembler.isTransientToolFailure(new IllegalStateException("other")))
                .isFalse();
    }

    @Test
    void profileRequiresGovernedExecutionButStillDisablesCodingWorkspaceCapabilities() {
        ProductContributionCoordinate coordinate = new ProductContributionCoordinate("test", "1");
        String mcpAlias = "personal_mcp_calculate";
        var profile = PersonalAssistantProfile.create(
                new PersonalAssistantProfile.ContributionCoordinates(
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate,
                        coordinate),
                Set.of(),
                Set.of(mcpAlias),
                Set.of(PersonalAssistantProfile.WEB_SEARCH_ALIAS, PersonalAssistantProfile.WEB_FETCH_ALIAS));
        assertThat(profile.requirement(ProductCapabilities.TOOL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.SKILL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.MCP).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.SHELL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.EXECUTION).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.APPROVAL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.CREDENTIAL).mode())
                .isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.ARTIFACT).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.PROJECT).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(profile.requirement(ProductCapabilities.WORKSPACE).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(profile.requirement(ProductCapabilities.GIT).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(profile.allowedTools())
                .contains(
                        PersonalAssistantProfile.PRODUCT_TOOL_ALIAS,
                        PersonalAssistantProfile.EXECUTION_TOOL_ALIAS,
                        PersonalAssistantProfile.SKILL_LOAD_ALIAS,
                        PersonalAssistantProfile.WEB_SEARCH_ALIAS,
                        PersonalAssistantProfile.WEB_FETCH_ALIAS,
                        mcpAlias);
        assertThat(profile.allowedSkills())
                .contains(PersonalAssistantProfile.BUNDLED_SKILL_ALIAS, PersonalAssistantProfile.EXECUTION_SKILL_ALIAS)
                .doesNotContain(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS);
        assertThat(profile.instructions())
                .contains(
                        "Treat the latest user message as the current objective",
                        "Do not resume or retry a previous failed or abandoned tool call");
        assertThat(profile.budget().maxInputTokens()).isEqualTo(512_000);
        assertThat(profile.budget().maxOutputTokens()).isEqualTo(128_000);
        assertThat(profile.budget().maxCachedInputTokens()).isEqualTo(512_000);
        assertThat(profile.budget().maxToolCalls()).isEqualTo(64);
        assertThat(profile.budget().maxModelCalls()).isEqualTo(64);
        assertThat(profile.limits().maxIterations()).isEqualTo(64);
    }

    @Test
    void assembledExecutionSchemaEnforcesTheModeContractAcrossHostLanguages() {
        for (String hostLanguage : List.of("powershell", "bash")) {
            var schema = ExecutionToolDefinitionFactory.create("profile@1", true, false, Set.of(hostLanguage))
                    .inputSchema();
            var validator = new ExecutionToolSchemaValidator(new JsonSchema202012Validator());

            assertThat(validator
                            .validate(
                                    schema,
                                    Map.of(
                                            "mode", "COMMAND",
                                            "content", "show-version",
                                            "purpose", "show host version"))
                            .valid())
                    .isTrue();
            assertThat(validator
                            .validate(
                                    schema,
                                    Map.of(
                                            "mode", "COMMAND",
                                            "language", hostLanguage,
                                            "content", "show-version",
                                            "purpose", "show host version"))
                            .errors())
                    .extracting("keyword")
                    .contains("combination");
            assertThat(validator
                            .validate(
                                    schema,
                                    Map.of(
                                            "mode", "SCRIPT",
                                            "language", hostLanguage,
                                            "content", "show-version",
                                            "purpose", "show host version"))
                            .valid())
                    .isTrue();
        }
    }
}
