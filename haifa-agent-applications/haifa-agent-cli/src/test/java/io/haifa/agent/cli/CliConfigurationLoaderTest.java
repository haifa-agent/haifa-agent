package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliConfigurationLoaderTest {
    @Test
    void loadsTrustedMultiModelConfigurationAndSelectsByInternalId() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-models", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: deepseek-v4-pro
                  providers:
                    - id: deepseek
                      displayName: DeepSeek
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      models:
                        - id: deepseek-v4-pro
                          displayName: DeepSeek V4 Pro
                          providerModelId: deepseek-v4-pro
                        - id: deepseek-v4-flash
                          displayName: DeepSeek V4 Flash
                          providerModelId: deepseek-v4-flash
                    - id: aliyun-bailian
                      displayName: Alibaba Cloud Bailian
                      workspaceId: workspace-123
                      region: cn-beijing
                      credentialRef: env://DASHSCOPE_API_KEY
                      models:
                        - id: bailian-qwen-plus
                          displayName: Qwen Plus
                          providerModelId: qwen-plus
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(
                                new String[] {"--config", configuration.toString(), "--model", "deepseek-v4-flash"}),
                        Path.of("."));

        assertThat(result.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash", "bailian-qwen-plus");
        assertThat(result.availableModels())
                .filteredOn(model -> model.providerId().equals("deepseek"))
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
        assertThat(result.model().id()).isEqualTo("deepseek-v4-flash");
        assertThat(result.model().modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(LocalCodingAgent.modelSnapshot(result).modelId().value()).isEqualTo("deepseek-v4-flash");
        assertThat(new CliCodingModelCatalog(result)
                        .available(
                                new io.haifa.agent.core.reference.TenantRef("local"),
                                new io.haifa.agent.core.reference.PrincipalRef("user", "user")))
                .filteredOn(model -> model.providerId().equals("deepseek"))
                .extracting(io.haifa.agent.application.project.product.coding.CodingModelOption::id)
                .containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void packagedDistributionConfigurationIsValidAndSecretFree() {
        Path configuration = Path.of("distribution", "haifa-coding.yaml").toAbsolutePath();

        CliConfiguration result = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));

        assertThat(result.model().providerId()).isEqualTo("deepseek");
        assertThat(result.model().id()).isEqualTo("deepseek-v4-flash");
        assertThat(result.model().credentialRef()).isEqualTo("env://DEEPSEEK_API_KEY");
        assertThat(result.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-v4-flash", "deepseek-v4-pro", "openai-gpt-5.6-luna");
        assertThat(result.availableModels())
                .filteredOn(model -> model.id().equals("openai-gpt-5.6-luna"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.providerId()).isEqualTo("openai");
                    assertThat(model.modelId()).isEqualTo("gpt-5.6-luna");
                    assertThat(model.endpoint()).hasToString("http://localhost:30000/v1");
                    assertThat(model.credentialRef()).isEqualTo("env://OPENAI_API_KEY");
                });
        assertThat(result.approval()).isEqualTo(ApprovalMode.ASK);
        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.execution().network()).isEqualTo("allow");
        assertThat(result.persistence().mode()).isEqualTo(ProjectPersistenceMode.MEMORY);
        assertThat(result.enabledTools()).contains("file.read", "file.write", "execution.run");
    }

    @Test
    void freezesOpenAiSecondProviderWithTheStandardChatCompletionsDialect() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-openai", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: deepseek-v4-flash
                  providers:
                    - id: deepseek
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      models:
                        - id: deepseek-v4-flash
                          providerModelId: deepseek-v4-flash
                    - id: openai
                      displayName: OpenAI
                      endpoint: http://localhost:30000/v1
                      credentialRef: env://OPENAI_API_KEY
                      models:
                        - id: openai-gpt-5.6-luna
                          displayName: GPT-5.6 Luna
                          providerModelId: gpt-5.6-luna
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(
                                new String[] {"--config", configuration.toString(), "--model", "openai-gpt-5.6-luna"}),
                        Path.of("."));
        var snapshot = LocalCodingAgent.modelSnapshot(result);

        assertThat(result.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-v4-flash", "openai-gpt-5.6-luna");
        assertThat(snapshot.providerId().value()).isEqualTo("openai");
        assertThat(snapshot.providerModelId()).isEqualTo("gpt-5.6-luna");
        assertThat(snapshot.providerOptions())
                .containsEntry("dialect_id", "openai-chat-completions")
                .containsEntry("dialect_version", "1.0")
                .doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void freezesDisabledDeepSeekThinkingForCliRuns() {
        CliConfiguration defaults = CliConfiguration.defaults();
        var snapshot = LocalCodingAgent.modelSnapshot(defaults);

        assertThat(defaults.model().providerId()).isEqualTo("deepseek");
        assertThat(defaults.model().id()).isEqualTo("deepseek-v4-flash");
        assertThat(defaults.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
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
                models:
                  default: test-model
                  providers:
                    - id: local
                      displayName: Local
                      endpoint: http://localhost:8080
                      credentialRef: env://TEST_KEY
                      models:
                        - id: test-model
                          displayName: Test model
                          providerModelId: test-model
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
        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.execution().network()).isEqualTo("allow");
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
    void rejectsLegacySingleModelConfiguration() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-legacy-model", ".yaml");
        Files.writeString(
                configuration,
                """
                model:
                  providerId: deepseek
                  modelId: deepseek-v4-flash
                """);

        assertThatThrownBy(() -> new CliConfigurationLoader()
                        .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models.providers");
    }

    @Test
    void loadsExplicitSqliteWithJsonlPersistenceConfiguration() throws Exception {
        Path root = Files.createTempDirectory("haifa-cli-persistence").toAbsolutePath();
        Path transcriptRoot = Files.createDirectory(root.resolve("transcripts"));
        Path database = root.resolve("runtime.db");
        Path configuration = Files.createTempFile("haifa-cli-persistence", ".yaml");
        Files.writeString(
                configuration,
                """
                persistence:
                  mode: SQLITE_WITH_JSONL
                  databasePath: '%s'
                  transcriptRoot: '%s'
                  protectorRef: env://HAIFA_TEST_CONTINUATION_KEY
                  busyTimeoutMillis: 750
                  maximumPayloadBytes: 1048576
                """
                        .formatted(
                                database.toString().replace("'", "''"),
                                transcriptRoot.toString().replace("'", "''")));

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "persistence", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.persistence().mode()).isEqualTo(ProjectPersistenceMode.SQLITE_WITH_JSONL);
        assertThat(result.persistence().databasePath()).contains(database);
        assertThat(result.persistence().transcriptRoot()).contains(transcriptRoot);
        assertThat(result.persistence().protectorReference()).contains("env://HAIFA_TEST_CONTINUATION_KEY");
        assertThat(result.persistence().busyTimeoutMillis()).isEqualTo(750);
        assertThat(result.persistence().maximumPayloadBytes()).isEqualTo(1_048_576);
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
                        defaults.provider(),
                        defaults.network(),
                        defaults.shell(),
                        defaults.shellPath(),
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        java.util.Set.of("DEEPSEEK_API_KEY"),
                        java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret-like");
        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        defaults.provider(),
                        defaults.network(),
                        "cmd",
                        null,
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        java.util.Set.of(),
                        java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }

    @Test
    void validatesProviderNetworkAndTrustedExtraPathConfiguration() throws Exception {
        Path cache = Files.createTempDirectory("haifa-cli-cache").toAbsolutePath();
        Path configuration = Files.createTempFile("haifa-cli-execution", ".yaml");
        Files.writeString(
                configuration,
                """
                execution:
                  provider: local-native
                  network: allow
                  extraPathPolicies:
                    - id: build-cache
                      path: "%s"
                      readOnly: false
                """
                        .formatted(cache.toString().replace("\\", "\\\\")));

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "execution", "--config", configuration.toString()}),
                        cache);

        assertThat(result.execution().provider()).isEqualTo("local-native");
        assertThat(result.execution().network()).isEqualTo("allow");
        assertThat(result.execution().extraPathPolicies())
                .containsExactly(new CliConfiguration.ExtraPathPolicy("build-cache", cache, false));

        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();
        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        "host-guarded",
                        "deny",
                        defaults.shell(),
                        defaults.shellPath(),
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        defaults.inheritEnvironment(),
                        java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
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
                .isEqualTo(io.haifa.agent.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT);
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
