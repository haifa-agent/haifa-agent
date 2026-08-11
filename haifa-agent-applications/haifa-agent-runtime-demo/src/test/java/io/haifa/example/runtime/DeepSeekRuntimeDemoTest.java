package io.haifa.example.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeepSeekRuntimeDemoTest {
    @Test
    void environmentApiKeyTakesPrecedenceWithoutPrompting() throws Exception {
        AtomicBoolean prompted = new AtomicBoolean();

        String value = DeepSeekRuntimeDemo.resolveApiKey("  env-secret  ", () -> {
            prompted.set(true);
            return "prompt-secret".toCharArray();
        });

        assertThat(value).isEqualTo("env-secret");
        assertThat(prompted).isFalse();
    }

    @Test
    void promptsWhenEnvironmentApiKeyIsMissingAndClearsPromptBuffer() throws Exception {
        char[] entered = "  prompt-secret  ".toCharArray();

        String value = DeepSeekRuntimeDemo.resolveApiKey(null, () -> entered);

        assertThat(value).isEqualTo("prompt-secret");
        assertThat(entered).containsOnly('\0');
    }

    @Test
    void rejectsBlankPromptedApiKey() {
        assertThatThrownBy(() -> DeepSeekRuntimeDemo.resolveApiKey(" ", () -> "  ".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DeepSeekRuntimeDemo.API_KEY_ENVIRONMENT_VARIABLE);
    }

    @Test
    void selectsModelOnlyScenarioByDefault() {
        var options = DeepSeekRuntimeDemo.parseOptions(new String[0]);

        assertThat(options.scenario()).isEqualTo(DeepSeekRuntimeDemo.ScenarioKind.MODEL_ONLY);
        assertThat(options.objective()).isEmpty();
    }

    @Test
    void selectsRawToolScenarioAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeDemo.parseOptions(new String[] {"use the echo result", "--with-tool"});

        assertThat(options.scenario()).isEqualTo(DeepSeekRuntimeDemo.ScenarioKind.RAW_TOOL);
        assertThat(options.objective()).contains("use the echo result");
    }

    @Test
    void selectsMcpScenarioAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeDemo.parseOptions(new String[] {"convert one meter", "--with-mcp"});

        assertThat(options.scenario()).isEqualTo(DeepSeekRuntimeDemo.ScenarioKind.MCP);
        assertThat(options.objective()).contains("convert one meter");
    }

    @Test
    void selectsSkillScenarioAndRemovesTheFlagFromTheObjective() {
        var options = DeepSeekRuntimeDemo.parseOptions(new String[] {"rewrite the space race", "--with-skill"});

        assertThat(options.scenario()).isEqualTo(DeepSeekRuntimeDemo.ScenarioKind.SKILL);
        assertThat(options.objective()).contains("rewrite the space race");
    }

    @Test
    void rejectsUnknownOptions() {
        assertThatThrownBy(() -> DeepSeekRuntimeDemo.parseOptions(new String[] {"--unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown option: --unknown");
    }

    @Test
    void rejectsEnablingMoreThanOneCapabilityScenario() {
        assertThatThrownBy(() ->
                        DeepSeekRuntimeDemo.parseOptions(new String[] {"--with-tool", "--with-mcp", "--with-skill"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void defaultsToTheLoopbackUtilityMcpEndpoint() {
        assertThat(DeepSeekRuntimeDemo.resolveMcpEndpoint(null)).isEqualTo(URI.create("http://127.0.0.1:20002/mcp"));
        assertThat(DeepSeekRuntimeDemo.resolveMcpEndpoint("  https://mcp.example.test/mcp  "))
                .isEqualTo(URI.create("https://mcp.example.test/mcp"));
    }
}
