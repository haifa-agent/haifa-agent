package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliConfigurationLoaderTest {
    @Test
    void freezesDisabledDeepSeekThinkingForCliRuns() {
        var snapshot = LocalCodingAgent.modelSnapshot(CliConfiguration.defaults());

        assertThat(snapshot.capabilities()).contains(ModelCapability.REASONING);
        assertThat(snapshot.providerOptions()).containsEntry("thinking", "disabled");
        assertThat(snapshot.providerOptions())
                .doesNotContainKeys("reasoning_effort", "requires_reasoning_continuation");
        assertThat(snapshot.invocationOptions()).containsEntry("thinking", "disabled");
        assertThat(snapshot.invocationOptions())
                .doesNotContainKeys("reasoning_effort", "requires_reasoning_continuation");
    }

    @Test
    void loadsExplicitYamlConfiguration() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli", ".yaml");
        Files.writeString(
                configuration,
                """
                model:
                  providerId: local
                  modelId: test-model
                  endpoint: http://localhost:8080
                  credentialRef: env://TEST_KEY
                tools:
                  enabled: [file.read, file.write]
                approval:
                  mode: deny
                execution:
                  shell: auto
                  defaultTimeoutMillis: 45000
                  maxTimeoutMillis: 600000
                  maxOutputBytes: 32768
                  maxOutputLines: 900
                  maxProcesses: 3
                  inheritEnvironment: [PATH, JAVA_HOME]
                runtime:
                  maxIterations: 3
                  maxToolCalls: 4
                  maxWallTimeMillis: 120000
                mcp:
                  servers:
                    - id: utility
                      displayName: Utility MCP
                      endpoint: http://127.0.0.1:8091/mcp
                      allowLoopbackHttp: true
                      allowedTools: [time_now, calculate]
                      aliasNamespace: utility
                      policyProfile: utility
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "test", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.model().modelId()).isEqualTo("test-model");
        assertThat(result.enabledTools()).containsExactlyInAnyOrder("file.read", "file.write");
        assertThat(result.approval()).isEqualTo(ApprovalMode.DENY);
        assertThat(result.timeout()).isEqualTo(java.time.Duration.ofMillis(120000));
        assertThat(result.execution().defaultTimeout()).isEqualTo(java.time.Duration.ofMillis(45000));
        assertThat(result.execution().maximumTimeout()).isEqualTo(java.time.Duration.ofMillis(600000));
        assertThat(result.execution().maxOutputBytes()).isEqualTo(32768);
        assertThat(result.execution().maxOutputLines()).isEqualTo(900);
        assertThat(result.execution().maxProcesses()).isEqualTo(3);
        assertThat(result.execution().inheritEnvironment()).containsExactlyInAnyOrder("PATH", "JAVA_HOME");
        assertThat(result.mcpServers()).singleElement().satisfies(server -> {
            assertThat(server.id()).isEqualTo("utility");
            assertThat(server.endpoint()).hasToString("http://127.0.0.1:8091/mcp");
            assertThat(server.allowedTools()).containsExactlyInAnyOrder("time_now", "calculate");
            assertThat(server.policyProfile()).isEqualTo("utility");
        });
    }

    @Test
    void rejectsSecretLikeEnvironmentInheritanceAndInvalidShellConfiguration() {
        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();

        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        defaults.shell(),
                        defaults.shellPath(),
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        java.util.Set.of("DEEPSEEK_API_KEY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret-like");
        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        "cmd",
                        null,
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }
}
