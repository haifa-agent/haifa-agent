package io.haifa.agent.examples.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.tool.api.ToolAlias;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeepSeekRuntimeMainTest {
    @Test
    void environmentApiKeyTakesPrecedenceWithoutPrompting() throws Exception {
        AtomicBoolean prompted = new AtomicBoolean();

        String value = DeepSeekRuntimeMain.resolveApiKey("  env-secret  ", () -> {
            prompted.set(true);
            return "prompt-secret".toCharArray();
        });

        assertThat(value).isEqualTo("env-secret");
        assertThat(prompted).isFalse();
    }

    @Test
    void promptsWhenEnvironmentApiKeyIsMissingAndClearsPromptBuffer() throws Exception {
        char[] entered = "  prompt-secret  ".toCharArray();

        String value = DeepSeekRuntimeMain.resolveApiKey(null, () -> entered);

        assertThat(value).isEqualTo("prompt-secret");
        assertThat(entered).containsOnly('\0');
    }

    @Test
    void rejectsBlankPromptedApiKey() {
        assertThatThrownBy(() -> DeepSeekRuntimeMain.resolveApiKey(" ", () -> "  ".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DeepSeekRuntimeMain.API_KEY_ENVIRONMENT_VARIABLE);
    }

    @Test
    void keepsSingleModelCallScenarioAsTheDefault() {
        var options = DeepSeekRuntimeMain.parseOptions(new String[0]);

        assertThat(options.toolEnabled()).isFalse();
        assertThat(options.mcpEnabled()).isFalse();
        assertThat(options.skillEnabled()).isFalse();
        assertThat(options.objective()).isEqualTo("Reply with exactly DEEPSEEK_V4_PRO_RUNTIME_OK.");
    }

    @Test
    void enablesTheEchoToolAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeMain.parseOptions(new String[] {"use the echo result", "--with-tool"});

        assertThat(options.toolEnabled()).isTrue();
        assertThat(options.mcpEnabled()).isFalse();
        assertThat(options.skillEnabled()).isFalse();
        assertThat(options.objective()).isEqualTo("use the echo result");
    }

    @Test
    void enablesOneUtilityMcpCallAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeMain.parseOptions(new String[] {"convert one meter", "--with-mcp"});

        assertThat(options.toolEnabled()).isFalse();
        assertThat(options.mcpEnabled()).isTrue();
        assertThat(options.skillEnabled()).isFalse();
        assertThat(options.objective()).isEqualTo("convert one meter");
    }

    @Test
    void enablesTheCounterfactualNewsroomSkillAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeMain.parseOptions(new String[] {"rewrite the space race", "--with-skill"});

        assertThat(options.toolEnabled()).isFalse();
        assertThat(options.mcpEnabled()).isFalse();
        assertThat(options.skillEnabled()).isTrue();
        assertThat(options.objective()).isEqualTo("rewrite the space race");
    }

    @Test
    void providesAThreeEraCounterfactualAsTheDefaultSkillObjective() {
        var options = DeepSeekRuntimeMain.parseOptions(new String[] {"--with-skill"});

        assertThat(options.objective()).contains("1996", "2008", "2025", "因果连续性");
    }

    @Test
    void createsOnePureLowRiskEchoBinding() {
        var catalog = DeepSeekRuntimeMain.echoToolCatalog();

        assertThat(catalog.snapshot().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.alias()).isEqualTo(new ToolAlias(DeepSeekRuntimeMain.ECHO_TOOL_ALIAS));
            assertThat(binding.definition().sideEffects()).isEmpty();
            assertThat(binding.definition().risk().name()).isEqualTo("LOW");
            assertThat(binding.definition().idempotency().name()).isEqualTo("PURE");
        });
    }

    @Test
    void rejectsUnknownOptions() {
        assertThatThrownBy(() -> DeepSeekRuntimeMain.parseOptions(new String[] {"--unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown option: --unknown");
    }

    @Test
    void rejectsEnablingMoreThanOneCapabilityScenario() {
        assertThatThrownBy(() ->
                        DeepSeekRuntimeMain.parseOptions(new String[] {"--with-tool", "--with-mcp", "--with-skill"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void defaultsToTheLoopbackUtilityMcpEndpoint() {
        assertThat(DeepSeekRuntimeMain.resolveMcpEndpoint(null)).isEqualTo(URI.create("http://127.0.0.1:20002/mcp"));
        assertThat(DeepSeekRuntimeMain.resolveMcpEndpoint("  https://mcp.example.test/mcp  "))
                .isEqualTo(URI.create("https://mcp.example.test/mcp"));
    }

    @Test
    void reviewsOnlyThePureLowRiskUnitConversionMcpTool() {
        var server = UtilityMcpRuntimePlatform.serverDefinition(URI.create("http://127.0.0.1:20002/mcp"));
        var policy = server.importPolicy();

        assertThat(policy.allowedTools()).isEqualTo(Set.of(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME));
        assertThat(policy.riskOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("LOW");
        assertThat(policy.idempotencyOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("PURE");
        assertThat(policy.sideEffectOverrides().get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME))
                .isEmpty();
        assertThat(policy.approvalOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("NEVER");
    }

    @Test
    void freezesAndLoadsTheCounterfactualNewsroomSkill() {
        var persistence = RuntimePersistencePorts.inMemory();
        var platform =
                CounterfactualNewsroomSkillPlatform.create(persistence, () -> Instant.parse("2026-07-30T00:00:00Z"));

        assertThat(platform.skillCatalog().snapshot().bindings())
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.alias().value()).isEqualTo(CounterfactualNewsroomSkillPlatform.SKILL_NAME);
                    assertThat(binding.metadata().description()).contains("alternate-history", "causal chain");
                    var visibility = new SkillVisibilityContext(
                            new TenantRef("local"),
                            new PrincipalRef("local-user", "user"),
                            Optional.empty(),
                            false,
                            Set.of(SkillScope.PRODUCT));
                    var content = platform.contentLoader().load(binding, visibility);
                    assertThat(content.instructions())
                            .contains("Build the causal spine", "Run the continuity desk", "compact-edition mode");
                });
        assertThat(platform.toolCatalog().snapshot().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.alias().value()).isEqualTo(CounterfactualNewsroomSkillPlatform.SKILL_LOAD_ALIAS);
            assertThat(binding.definition().name().value()).isEqualTo("skill.load");
        });
    }
}
