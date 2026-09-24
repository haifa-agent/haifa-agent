package io.haifa.agent.personalassistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolSchemaValidator;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
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
        var profile = PersonalAssistantProfile.create(
                Set.of(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS, "user-skill"), Set.of(), Set.of(), Set.of());

        assertThat(profile.allowedSkills())
                .contains(
                        PersonalAssistantProfile.BUNDLED_SKILL_ALIAS,
                        PersonalAssistantProfile.GITHUB_PROJECT_WATCH_SKILL_ALIAS,
                        "user-skill")
                .doesNotContain(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS, "git", "github");
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
    void activeHistoryBudgetConfiguredDynamically() {
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_ACTIVE_HISTORY_BUDGET_PERCENT)
                .isEqualTo(25);
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_MIN_ACTIVE_HISTORY_BUDGET_TOKENS)
                .isEqualTo(48_000L);
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_MAX_ACTIVE_HISTORY_BUDGET_TOKENS)
                .isEqualTo(96_000L);
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_TARGET_TAIL_TOKEN_PERCENT)
                .isEqualTo(40);
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_MIN_TAIL_TOKENS)
                .isEqualTo(24_000);
        assertThat(PersonalAssistantAssembler.PERSONAL_ASSISTANT_MAX_TAIL_TOKENS)
                .isEqualTo(32_000);

        var policy = PersonalAssistantAssembler.defaultCompressionPolicy();
        assertThat(policy.semanticCompactionEnabled()).isTrue();
        // A rejected summary degrades to the deterministic compressor instead of failing the user's Run.
        assertThat(policy.allowDeterministicDegradedFallback()).isTrue();
        assertThat(policy.activeHistoryBudgetPercent()).isEqualTo(25);
        assertThat(policy.minActiveHistoryBudgetTokens()).isEqualTo(48_000L);
        assertThat(policy.maxActiveHistoryBudgetTokens()).isEqualTo(96_000L);
        assertThat(policy.targetTailTokenPercent()).isEqualTo(40);
        assertThat(policy.minTailTokens()).isEqualTo(24_000);
        assertThat(policy.maxTailTokens()).isEqualTo(32_000);
        assertThat(policy.activeHistoryBudgetTokens()).isEmpty();
    }

    @Test
    void profileDisclosesGovernedExecutionToolsAndSkills() {
        String mcpAlias = "personal_mcp_calculate";
        var profile = PersonalAssistantProfile.create(
                Set.of(),
                Set.of(mcpAlias),
                Set.of(PersonalAssistantProfile.WEB_SEARCH_ALIAS, PersonalAssistantProfile.WEB_FETCH_ALIAS),
                Set.of());
        assertThat(profile.allowedTools())
                .contains(
                        PersonalAssistantProfile.PRODUCT_TOOL_ALIAS,
                        PersonalAssistantProfile.EXECUTION_TOOL_ALIAS,
                        PersonalAssistantProfile.SKILL_LOAD_ALIAS,
                        PersonalAssistantProfile.WEB_SEARCH_ALIAS,
                        PersonalAssistantProfile.WEB_FETCH_ALIAS,
                        mcpAlias);
        assertThat(profile.allowedSkills())
                .contains(
                        PersonalAssistantProfile.BUNDLED_SKILL_ALIAS,
                        PersonalAssistantProfile.EXECUTION_SKILL_ALIAS,
                        PersonalAssistantProfile.GITHUB_PROJECT_WATCH_SKILL_ALIAS)
                .doesNotContain(PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS, "git", "github");
        assertThat(profile.instructions())
                .contains(
                        "Treat the latest user message as the current objective",
                        "Do not resume or retry a failed or abandoned tool call from a previous task",
                        "judge progress from actual results",
                        "Never bypass authorization");
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
