package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
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
    void derivesBailianEndpointAndFreezesThinkingDisabledProfile() {
        var model = new CliConfiguration.Model(
                "aliyun-bailian", "qwen-plus", null, "env://DASHSCOPE_API_KEY", "workspace-123", null);
        CliConfiguration defaults = CliConfiguration.defaults();
        var snapshot = LocalCodingAgent.modelSnapshot(new CliConfiguration(
                model,
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.execution(),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls()));

        assertThat(model.workspaceId()).isEqualTo("workspace-123");
        assertThat(model.region()).isEqualTo("cn-beijing");
        assertThat(model.endpoint())
                .hasToString("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        assertThat(snapshot.providerId().value()).isEqualTo("aliyun-bailian");
        assertThat(snapshot.providerOptions())
                .containsEntry("dialect_id", "aliyun-bailian-openai-chat")
                .containsEntry("workspace_id", "workspace-123")
                .containsEntry("region", "cn-beijing");
        assertThat(snapshot.invocationOptions())
                .containsEntry("thinking_profile", "none")
                .containsEntry("thinking_enabled", false);
        assertThat(snapshot.capabilities()).doesNotContain(ModelCapability.REASONING);
    }

    @Test
    void rejectsBailianEndpointThatDoesNotMatchWorkspaceAndRegion() {
        assertThatThrownBy(() -> new CliConfiguration.Model(
                        "aliyun-bailian",
                        "qwen-plus",
                        java.net.URI.create("https://example.com/compatible-mode/v1"),
                        "env://DASHSCOPE_API_KEY",
                        "workspace-123",
                        "cn-beijing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived from workspaceId and region");
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
    void loadsExplicitLocalUserSkillDirectoryAndAllowlist() throws Exception {
        Path skillRoot = Files.createTempDirectory("haifa-cli-skills").toAbsolutePath();
        Path configuration = Files.createTempFile("haifa-cli-skills", ".yaml");
        String yamlRoot = skillRoot.toString().replace("'", "''");
        Files.writeString(
                configuration,
                """
                skills:
                  allowed: [task-planning, local-test]
                  localDirectories:
                    - id: personal
                      root: '%s'
                      priority: 250
                      parserMode: compatible
                      origin: imported
                """
                        .formatted(yamlRoot));

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "skills", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.skills().allowedAliases()).containsExactlyInAnyOrder("task-planning", "local-test");
        assertThat(result.skills().localDirectories()).singleElement().satisfies(directory -> {
            assertThat(directory.id()).isEqualTo("personal");
            assertThat(directory.root()).isEqualTo(skillRoot.normalize());
            assertThat(directory.priority()).isEqualTo(250);
            assertThat(directory.parserMode()).isEqualTo(SkillParserMode.COMPATIBLE);
            assertThat(directory.origin()).isEqualTo(SkillOrigin.IMPORTED);
        });
    }

    @Test
    void rejectsRelativeOrDuplicateLocalSkillDirectories() {
        assertThatThrownBy(() -> new CliConfiguration.LocalSkillDirectory(
                        "personal", Path.of("skills"), 100, SkillParserMode.STRICT, SkillOrigin.CREATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");

        Path root = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("haifa-cli-skill-root")
                .toAbsolutePath();
        var first = new CliConfiguration.LocalSkillDirectory(
                "personal", root, 100, SkillParserMode.STRICT, SkillOrigin.CREATED);
        var duplicate = new CliConfiguration.LocalSkillDirectory(
                "personal", root.resolve("other"), 100, SkillParserMode.STRICT, SkillOrigin.CREATED);
        assertThatThrownBy(() -> new CliConfiguration.Skills(
                        java.util.Set.of("local-test"), java.util.List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ids must be unique");
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

    @Test
    void loadsExplicitWebSearchConfigurationWithProviderDefaults() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-web", ".yaml");
        Files.writeString(
                configuration,
                """
                tools:
                  enabled: [file.read, web.search]
                web:
                  search:
                    enabled: true
                    provider: brave
                  fetch:
                    enabled: false
                    provider: aliyun
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "web", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.web().search().enabled()).isTrue();
        assertThat(result.web().search().providerId()).isEqualTo("brave");
        assertThat(result.web().search().endpoint())
                .isEqualTo(
                        io.haifa.agent.application.project.tool.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT);
        assertThat(result.web().search().credentialRef()).isEqualTo("env://BRAVE_SEARCH_API_KEY");
        assertThat(result.web().fetch().enabled()).isFalse();
    }

    @Test
    void rejectsWebToolAndProviderEnablementMismatch() {
        CliConfiguration defaults = CliConfiguration.defaults();

        assertThatThrownBy(() -> new CliConfiguration(
                        defaults.model(),
                        java.util.stream.Stream.concat(
                                        defaults.enabledTools().stream(), java.util.stream.Stream.of("web.search"))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        defaults.mcpServers(),
                        defaults.web(),
                        defaults.execution(),
                        defaults.approval(),
                        defaults.timeout(),
                        defaults.maxIterations(),
                        defaults.maxToolCalls()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }
}
