package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.application.project.persistence.ProjectPersistenceProtection;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliFeatureConfigurationTest {
    @Test
    void defaultsToGenericOsCliSearchWhileKeepingFileSearchAsExplicitCompatibilityTool() {
        CliConfiguration defaults = CliConfiguration.defaults();

        assertThat(defaults.enabledTools()).contains("execution_run").doesNotContain("file_search");
        assertThat(defaults.maxModelCalls()).isEqualTo(64);

        var explicitCompatibilityConfiguration = new CliConfiguration(
                defaults.model(),
                Set.of("file_search"),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                defaults.execution(),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                defaults.persistence());
        assertThat(explicitCompatibilityConfiguration.enabledTools()).containsExactly("file_search");
    }

    @Test
    void resolvesExplicitApprovalThresholdAndCompatibilityModes() throws Exception {
        Path thresholdConfiguration = Files.createTempFile("haifa-cli-threshold", ".yaml");
        Files.writeString(thresholdConfiguration, "approval:\n  threshold: high\n");
        Path autoConfiguration = Files.createTempFile("haifa-cli-auto", ".yaml");
        Files.writeString(autoConfiguration, "approval:\n  mode: auto\n");

        CliConfiguration threshold = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", thresholdConfiguration.toString()}), Path.of("."));
        CliConfiguration auto = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", autoConfiguration.toString()}), Path.of("."));
        CliConfiguration override = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(
                                new String[] {"--config", thresholdConfiguration.toString(), "--approval", "auto"}),
                        Path.of("."));

        assertThat(threshold.approval()).isEqualTo(ApprovalMode.ASK);
        assertThat(threshold.approvalThreshold()).isEqualTo(CodingApprovalThreshold.HIGH);
        assertThat(auto.approvalThreshold()).isEqualTo(CodingApprovalThreshold.NEVER);
        assertThat(override.approvalThreshold()).isEqualTo(CodingApprovalThreshold.NEVER);
    }

    @Test
    void rejectsConflictingApprovalModeAndThreshold() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-threshold-conflict", ".yaml");
        Files.writeString(configuration, "approval:\n  mode: ask\n  threshold: high\n");

        assertThatThrownBy(() -> new CliConfigurationLoader()
                        .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval.mode and approval.threshold conflict");
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
                          nativeStreaming: true
                          endpoint: http://localhost:8080
                          credentialRef: env://TEST_KEY
                          apiBindings:
                            - style: openai-chat-completions
                          models:
                            - id: test-model
                              displayName: Test model
                              providerModelId: test-model
                              style: openai-chat-completions
                              capabilities: [TEXT_CHAT, TOOL_CALLING]
                              contextWindow: 8192
                              maxOutputTokens: 1024
                    tools:
                      enabled: [file_read, file_write]
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
                      maxModelCalls: 5
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
        assertThat(result.enabledTools()).containsExactlyInAnyOrder("file_read", "file_write");
        assertThat(result.approval()).isEqualTo(ApprovalMode.DENY);
        assertThat(result.timeout()).isEqualTo(java.time.Duration.ofMillis(120000));
        assertThat(result.maxModelCalls()).isEqualTo(5);
        assertThat(result.execution().defaultTimeout()).isEqualTo(java.time.Duration.ofMillis(45000));
        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.execution().maximumTimeout()).isEqualTo(java.time.Duration.ofMillis(600000));
        assertThat(result.execution().maxOutputBytes()).isEqualTo(32768);
        assertThat(result.execution().maxOutputLines()).isEqualTo(900);
        assertThat(result.execution().maxProcesses()).contains(3);
        assertThat(result.execution().inheritEnvironment()).containsExactlyInAnyOrder("PATH", "JAVA_HOME");
        assertThat(result.mcpServers()).singleElement().satisfies(server -> {
            assertThat(server.id()).isEqualTo("utility");
            assertThat(server.endpoint()).hasToString("http://127.0.0.1:8091/mcp");
            assertThat(server.allowedTools()).containsExactlyInAnyOrder("time_now", "calculate");
            assertThat(server.policyProfile()).isEqualTo("utility");
        });
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
        assertThat(result.persistence().protection()).isEqualTo(ProjectPersistenceProtection.AES_GCM);
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
    void expandsEnvironmentPlaceholderForLocalSkillDirectory() throws Exception {
        Path skillRoot =
                Files.createTempDirectory("haifa-cli-environment-skills").toAbsolutePath();
        Path configuration = Files.createTempFile("haifa-cli-environment-skills", ".yaml");
        Files.writeString(
                configuration,
                """
                    skills:
                      allowed: [local-test]
                      localDirectories:
                        - id: reviewed-test-skills
                          root: ${HAIFA_TEST_SKILL_ROOT}
                          priority: 100
                          parserMode: strict
                          origin: imported
                    """);

        CliConfiguration result = new CliConfigurationLoader(
                        name -> name.equals("HAIFA_TEST_SKILL_ROOT") ? skillRoot.toString() : null)
                .load(
                        CliArguments.parse(new String[] {"-m", "skills", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.skills().localDirectories())
                .singleElement()
                .extracting(CliConfiguration.LocalSkillDirectory::root)
                .isEqualTo(skillRoot.normalize());
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
                        defaults.provider(),
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
    void ignoresRetiredIsolationKeysAndKeepsTheSingleHostProvider() throws Exception {
        Path cache = Files.createTempDirectory("haifa-cli-cache").toAbsolutePath();
        Path configuration = Files.createTempFile("haifa-cli-execution", ".yaml");
        Files.writeString(
                configuration,
                """
                    execution:
                      provider: host-guarded
                      network: allow
                      maxProcesses: 4
                    """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "execution", "--config", configuration.toString()}),
                        cache);

        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.execution().maxProcesses()).contains(4);

        Path minimalConfig = Files.createTempFile("haifa-cli-default-proc", ".yaml");
        Files.writeString(minimalConfig, "execution:\n  provider: host-guarded\n");
        CliConfiguration defaultResult = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "execution", "--config", minimalConfig.toString()}),
                        cache);
        assertThat(defaultResult.execution().maxProcesses()).isEmpty();

        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();
        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        "local-native",
                        defaults.shell(),
                        defaults.shellPath(),
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        defaults.inheritEnvironment()))
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
                      enabled: [file_read, web_search]
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
    void loadsBrowserlessFetchWithSecureDefaults() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-browserless", ".yaml");
        Files.writeString(
                configuration,
                """
                    tools:
                      enabled: [file_read, web_fetch]
                    web:
                      search:
                        enabled: false
                        provider: aliyun
                      fetch:
                        enabled: true
                        provider: browserless
                    """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "web", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.web().fetch().enabled()).isTrue();
        assertThat(result.web().fetch().providerId()).isEqualTo("browserless");
        assertThat(result.web().fetch().endpoint())
                .isEqualTo(io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT);
        assertThat(result.web().fetch().credentialRef()).isEqualTo("env://BROWSERLESS_TOKEN");
        assertThat(result.web().fetch().endpoint().getQuery()).isNull();
    }

    @Test
    void loadsTavilyFetchWithSharedProviderCredentialDefault() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-tavily-fetch", ".yaml");
        Files.writeString(
                configuration,
                """
                    tools:
                      enabled: [file_read, web_fetch]
                    web:
                      search:
                        enabled: false
                        provider: aliyun
                      fetch:
                        enabled: true
                        provider: tavily
                    """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "web", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.web().fetch().endpoint())
                .isEqualTo(io.haifa.agent.web.provider.TavilyFetchProvider.DEFAULT_ENDPOINT);
        assertThat(result.web().fetch().credentialRef()).isEqualTo("env://TAVILY_API_KEY");
    }

    @Test
    void rejectsWebToolAndProviderEnablementMismatch() {
        CliConfiguration defaults = CliConfiguration.defaults();

        assertThatThrownBy(() -> new CliConfiguration(
                        defaults.model(),
                        java.util.stream.Stream.concat(
                                        defaults.enabledTools().stream(), java.util.stream.Stream.of("web_search"))
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
