package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.application.project.persistence.ProjectPersistenceProtection;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliConfigurationLoaderTest {
    @Test
    void loadsTheDedicatedCodexResponsesBindingWithoutEmbeddingAClientId() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-codex", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: codex
                  providers:
                    - id: openai-codex
                      displayName: ChatGPT Codex
                      nativeStreaming: true
                      endpoint: https://chatgpt.com/backend-api/codex
                      credentialRef: model-auth://openai-codex/default
                      originator: haifa
                      userAgent: haifa-agent/1
                      apiBindings:
                        - style: openai-responses
                          dialect: openai-codex-responses
                      models:
                        - id: codex
                          displayName: Codex
                          providerModelId: codex-model
                          style: openai-responses
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 200000
                          maxOutputTokens: 8192
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));
        var snapshot = LocalCodingAgent.modelSnapshot(result);

        assertThat(result.model().dialect()).isEqualTo("openai-codex-responses");
        assertThat(result.model().credentialRef()).isEqualTo("model-auth://openai-codex/default");
        assertThat(snapshot.endpoint()).hasToString("https://chatgpt.com/backend-api/codex");
        assertThat(snapshot.providerOptions())
                .containsEntry("codex_originator", "haifa")
                .containsEntry("codex_user_agent", "haifa-agent/1")
                .doesNotContainKeys("client_id", "access_token", "refresh_token");
    }

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
                      nativeStreaming: true
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                          dialect: deepseek-openai-chat
                      models:
                        - id: deepseek-v4-pro
                          displayName: DeepSeek V4 Pro
                          providerModelId: deepseek-v4-pro
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
                        - id: deepseek-v4-flash
                          displayName: DeepSeek V4 Flash
                          providerModelId: deepseek-v4-flash
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
                    - id: aliyun-bailian
                      displayName: Alibaba Cloud Bailian
                      nativeStreaming: true
                      endpoint: https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
                      workspaceId: workspace-123
                      region: cn-beijing
                      credentialRef: env://DASHSCOPE_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                          dialect: aliyun-bailian-openai-chat
                      models:
                        - id: bailian-qwen-plus
                          displayName: Qwen Plus
                          providerModelId: qwen-plus
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
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
        assertThat(new CliCodingModelCatalog(
                                result,
                                model -> model.id().equals("deepseek-v4-flash")
                                        ? io.haifa.agent.application.project.product.coding.CodingModelState.Connection
                                                .LOGIN_REQUIRED
                                        : io.haifa.agent.application.project.product.coding.CodingModelState.Connection
                                                .CONNECTED)
                        .available(
                                new io.haifa.agent.core.reference.TenantRef("local"),
                                new io.haifa.agent.core.reference.PrincipalRef("user", "user")))
                .filteredOn(model -> model.id().equals("deepseek-v4-flash"))
                .singleElement()
                .extracting(model -> model.state().connection())
                .isEqualTo(
                        io.haifa.agent.application.project.product.coding.CodingModelState.Connection.LOGIN_REQUIRED);
    }

    @Test
    void packagedDistributionConfigurationIsValidAndSecretFree(@TempDir Path tempDirectory) throws Exception {
        Path template = Path.of("distribution", "haifa-coding.yaml").toAbsolutePath();
        Path database = tempDirectory.resolve("data").resolve("runtime.db");
        Path transcriptRoot = tempDirectory.resolve("data").resolve("transcripts");
        Path configuration = tempDirectory.resolve("haifa-coding.yaml");
        Files.writeString(
                configuration,
                Files.readString(template)
                        .replace(
                                "__HAIFA_SQLITE_DATABASE_PATH__",
                                "'" + database.toString().replace('\\', '/') + "'")
                        .replace(
                                "__HAIFA_TRANSCRIPT_ROOT__",
                                "'" + transcriptRoot.toString().replace('\\', '/') + "'"));

        CliConfiguration result = new CliConfigurationLoader(name -> switch (name) {
                    case "HAIFA_CODEX_ORIGINATOR" -> "pi";
                    case "HAIFA_CODEX_USER_AGENT" -> "haifa-agent-local-compat/1";
                    case "OPENAI_BASE_URL" -> "http://127.0.0.1:30000/v1";
                    case "OPENAI_MODEL_ID" -> "gpt-5.6-luna";
                    default -> null;
                })
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));

        assertThat(result.model().providerId()).isEqualTo("deepseek");
        assertThat(result.model().id()).isEqualTo("deepseek-responses-flash");
        assertThat(result.model().credentialRef()).isEqualTo("model-auth://deepseek/default");
        assertThat(result.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly(
                        "deepseek-chat-pro",
                        "deepseek-chat-flash",
                        "deepseek-responses-flash",
                        "deepseek-responses-pro",
                        "deepseek-anthropic-flash",
                        "deepseek-anthropic-pro",
                        "gpt-5.6-sol",
                        "gpt-5.6-terra",
                        "gpt-5.6-luna",
                        "qwen3.8-max-0902",
                        "qwen3.8-max",
                        "qwen3.8-flash",
                        "qwen3.7-plus",
                        "qwen3-vl-plus",
                        "siliconflow-deepseek-v4-pro",
                        "siliconflow-deepseek-v4-flash",
                        "siliconflow-qwen3-vl-32b",
                        "siliconflow-qwen3-32b",
                        "siliconflow-kimi-k3",
                        "siliconflow-kimi-k2-6",
                        "siliconflow-glm-5-2",
                        "siliconflow-glm-5-1",
                        "kimi-k3",
                        "kimi-k2.7-code",
                        "kimi-k2.6",
                        "glm-5.2",
                        "glm-5.1",
                        "glm-5",
                        "glm-5-turbo");
        assertThat(result.availableModels())
                .filteredOn(model -> model.id().equals("gpt-5.6-sol"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.providerId()).isEqualTo("openai-codex");
                    assertThat(model.modelId()).isEqualTo("gpt-5.6-sol");
                    assertThat(model.endpoint()).hasToString("https://chatgpt.com/backend-api/codex");
                    assertThat(model.credentialRef()).isEqualTo("model-auth://openai-codex/default");
                    assertThat(model.style()).isEqualTo(ModelApiStyles.OPENAI_RESPONSES);
                    assertThat(model.dialect()).isEqualTo("openai-codex-responses");
                    assertThat(model.originator()).isEqualTo("pi");
                    assertThat(model.userAgent()).isEqualTo("haifa-agent-local-compat/1");
                });
        assertThat(result.availableModels())
                .filteredOn(model -> model.id().equals("qwen3-vl-plus"))
                .singleElement()
                .satisfies(model -> assertThat(model.capabilities())
                        .contains(
                                io.haifa.agent.model.api.ModelCapability.IMAGE_UPLOAD_INPUT,
                                io.haifa.agent.model.api.ModelCapability.IMAGE_URL_INPUT));
        assertThat(result.availableModels())
                .filteredOn(model -> model.id().equals("siliconflow-glm-5-2"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.modelId()).isEqualTo("zai-org/GLM-5.2");
                    assertThat(model.contextWindow()).isEqualTo(1_048_576);
                });
        CliConfiguration antigravityEnabled = new CliConfigurationLoader(name -> switch (name) {
                    case "HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST" -> "true";
                    case "HAIFA_CODEX_ORIGINATOR" -> "pi";
                    case "HAIFA_CODEX_USER_AGENT" -> "haifa-agent-local-compat/1";
                    case "OPENAI_BASE_URL" -> "http://127.0.0.1:30000/v1";
                    case "OPENAI_MODEL_ID" -> "gpt-5.6-luna";
                    default -> null;
                })
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));
        assertThat(antigravityEnabled.availableModels())
                .filteredOn(model -> model.id().equals("antigravity-gemini"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.providerId()).isEqualTo("google-antigravity");
                    assertThat(model.modelId()).isEqualTo("gemini-3-flash");
                    assertThat(model.endpoint()).hasToString("https://daily-cloudcode-pa.googleapis.com/v1internal");
                    assertThat(model.credentialRef()).isEqualTo("model-auth://google-antigravity/default");
                    assertThat(model.dialect()).isEqualTo("antigravity-direct");
                });
        assertThat(result.availableModels())
                .filteredOn(model -> model.id().equals("deepseek-anthropic-flash"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.style()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
                    assertThat(model.dialect()).isEqualTo("deepseek-anthropic-messages");
                    assertThat(model.endpoint()).hasToString("https://api.deepseek.com/anthropic");
                });
        assertThat(new CliCodingModelCatalog(result)
                        .available(
                                new io.haifa.agent.core.reference.TenantRef("local"),
                                new io.haifa.agent.core.reference.PrincipalRef("user", "user")))
                .extracting(io.haifa.agent.application.project.product.coding.CodingModelOption::id)
                .contains("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
                .doesNotContain("local-openai-responses");
        assertThat(result.approval()).isEqualTo(ApprovalMode.ASK);
        assertThat(result.approvalThreshold()).isEqualTo(CodingApprovalThreshold.LOW);
        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.execution().network()).isEqualTo("allow");
        assertThat(result.persistence().mode()).isEqualTo(ProjectPersistenceMode.SQLITE_WITH_JSONL);
        assertThat(result.persistence().protection()).isEqualTo(ProjectPersistenceProtection.NONE);
        assertThat(result.persistence().databasePath()).contains(database);
        assertThat(result.persistence().transcriptRoot()).contains(transcriptRoot);
        assertThat(result.persistence().protectorReference()).isEmpty();
        assertThat(result.enabledTools())
                .contains("file.read", "file.write", "execution.run")
                .doesNotContain("file.search");
    }

    @Test
    void defaultsToGenericOsCliSearchWhileKeepingFileSearchAsExplicitCompatibilityTool() {
        CliConfiguration defaults = CliConfiguration.defaults();

        assertThat(defaults.enabledTools()).contains("execution.run").doesNotContain("file.search");

        var explicitCompatibilityConfiguration = new CliConfiguration(
                defaults.model(),
                Set.of("file.search"),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                defaults.execution(),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                defaults.persistence());
        assertThat(explicitCompatibilityConfiguration.enabledTools()).containsExactly("file.search");
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
    void freezesOpenAiSecondProviderWithTheStandardChatCompletionsDialect() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-openai", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: deepseek-v4-flash
                  providers:
                    - id: deepseek
                      nativeStreaming: true
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                          dialect: deepseek-openai-chat
                      models:
                        - id: deepseek-v4-flash
                          providerModelId: deepseek-v4-flash
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
                    - id: openai
                      displayName: OpenAI
                      nativeStreaming: false
                      endpoint: http://localhost:30000/v1
                      credentialRef: env://OPENAI_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                      models:
                        - id: openai-gpt-5.6-luna
                          displayName: GPT-5.6 Luna
                          providerModelId: gpt-5.6-luna
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
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
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        assertThat(snapshot.dialect()).isEqualTo("standard");
        assertThat(snapshot.nativeStreaming()).isFalse();
        assertThat(snapshot.providerOptions()).doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void freezesExplicitStandardDialectForAnArbitraryProviderId() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-third-party-openai", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: third-party-chat
                  providers:
                    - id: third-party-openai
                      displayName: Third-party OpenAI-compatible
                      nativeStreaming: true
                      endpoint: https://gateway.example.com/v1
                      credentialRef: env://THIRD_PARTY_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                      models:
                        - id: third-party-chat
                          displayName: Third-party Chat
                          providerModelId: vendor-chat-model
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));
        var snapshot = LocalCodingAgent.modelSnapshot(result);

        assertThat(snapshot.providerId().value()).isEqualTo("third-party-openai");
        assertThat(snapshot.providerModelId()).isEqualTo("vendor-chat-model");
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        assertThat(snapshot.dialect()).isEqualTo("standard");
        assertThat(snapshot.nativeStreaming()).isTrue();
        assertThat(snapshot.providerOptions())
                .containsEntry("endpoint_host", "gateway.example.com")
                .doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void freezesDisabledDeepSeekThinkingForCliRuns() {
        CliConfiguration defaults = CliConfiguration.defaults();
        var snapshot = LocalCodingAgent.modelSnapshot(defaults);

        assertThat(defaults.model().providerId()).isEqualTo("deepseek");
        assertThat(defaults.model().id()).isEqualTo("deepseek-responses-flash");
        assertThat(defaults.availableModels())
                .extracting(CliConfiguration.Model::id)
                .containsExactly("deepseek-responses-flash", "deepseek-chat-pro", "deepseek-anthropic-flash");
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.OPENAI_RESPONSES);
        assertThat(snapshot.dialect()).isEqualTo("deepseek-openai-responses");
        assertThat(snapshot.capabilities()).contains(ModelCapability.REASONING);
        assertThat(snapshot.providerOptions())
                .doesNotContainKeys("thinking", "reasoning_effort", "requires_reasoning_continuation");
        assertThat(snapshot.invocationOptions())
                .doesNotContainKeys("thinking", "reasoning_effort", "requires_reasoning_continuation");
    }

    @Test
    void freezesDeepSeekAnthropicEndpointAndDisabledThinking() {
        var model = CliConfiguration.defaults().availableModels().stream()
                .filter(candidate -> candidate.id().equals("deepseek-anthropic-flash"))
                .findFirst()
                .orElseThrow();

        var snapshot = LocalCodingAgent.modelSnapshot(model);

        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
        assertThat(snapshot.adapterType()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER);
        assertThat(snapshot.dialect()).isEqualTo("deepseek-anthropic-messages");
        assertThat(snapshot.endpoint()).hasToString("https://api.deepseek.com/anthropic");
        assertThat(snapshot.invocationOptions()).containsEntry("thinking", "disabled");
    }

    @Test
    void freezesEnabledReasoningForBailianResponses() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-bailian-responses", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: bailian-responses-qwen
                  providers:
                    - id: aliyun-bailian
                      displayName: Alibaba Cloud Bailian
                      nativeStreaming: true
                      endpoint: ${HAIFA_BAILIAN_ENDPOINT}
                      credentialRef: env://DASHSCOPE_API_KEY
                      apiBindings:
                        - style: openai-responses
                          dialect: aliyun-bailian-openai-responses
                      models:
                        - id: bailian-responses-qwen
                          displayName: Qwen 3.7 Max Responses
                          providerModelId: qwen3.7-max
                          style: openai-responses
                          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
                          contextWindow: 1000000
                          maxOutputTokens: 65536
                          reasoningMode: enabled
                """);
        String endpoint = "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

        CliConfiguration result = new CliConfigurationLoader(
                        name -> name.equals("HAIFA_BAILIAN_ENDPOINT") ? endpoint : null)
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));
        var snapshot = LocalCodingAgent.modelSnapshot(result);

        assertThat(snapshot.providerId().value()).isEqualTo("aliyun-bailian");
        assertThat(snapshot.providerModelId()).isEqualTo("qwen3.7-max");
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.OPENAI_RESPONSES);
        assertThat(snapshot.dialect()).isEqualTo("aliyun-bailian-openai-responses");
        assertThat(snapshot.endpoint()).hasToString(endpoint);
        assertThat(snapshot.invocationOptions()).containsEntry("reasoning_effort", "high");
        assertThat(snapshot.providerOptions()).doesNotContainKeys("thinking", "reasoning_effort");
    }

    @Test
    void derivesBailianEndpointAndFreezesThinkingDisabledProfile() {
        var model = new CliConfiguration.Model(
                "aliyun-bailian",
                "Alibaba Cloud Bailian",
                "qwen-plus",
                URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
                URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
                "env://DASHSCOPE_API_KEY",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                true,
                "workspace-123",
                null,
                "qwen-plus",
                "Qwen Plus",
                java.util.Set.of(ModelCapability.TEXT_CHAT),
                131_072,
                8_192);
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
        assertThat(snapshot.dialect()).isEqualTo(OpenAiCompatibleDialects.ALIYUN_BAILIAN);
        assertThat(snapshot.providerModelId()).isEqualTo("qwen-plus");
        assertThat(snapshot.providerOptions())
                .containsEntry("workspace_id", "workspace-123")
                .containsEntry("region", "cn-beijing");
        assertThat(snapshot.invocationOptions())
                .containsEntry("thinking_profile", "none")
                .containsEntry("thinking_enabled", false);
        assertThat(snapshot.capabilities()).doesNotContain(ModelCapability.REASONING);
    }

    @Test
    void loadsProviderNeutralReasoningModeForBailianThinkingModel() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-reasoning-model", ".yaml");
        Files.writeString(
                configuration,
                """
                models:
                  default: reasoning-model
                  providers:
                    - id: aliyun-bailian
                      displayName: Alibaba Cloud Bailian
                      nativeStreaming: true
                      endpoint: https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
                      workspaceId: workspace-123
                      region: cn-beijing
                      credentialRef: env://DASHSCOPE_API_KEY
                      apiBindings:
                        - style: openai-chat-completions
                          dialect: aliyun-bailian-openai-chat
                      models:
                        - id: reasoning-model
                          displayName: Reasoning Model
                          providerModelId: reasoning-model
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT, TOOL_CALLING, REASONING]
                          contextWindow: 131072
                          maxOutputTokens: 8192
                          reasoningMode: enabled
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(CliArguments.parse(new String[] {"--config", configuration.toString()}), Path.of("."));
        var snapshot = LocalCodingAgent.modelSnapshot(result);

        assertThat(result.model().reasoningMode()).isEqualTo(ModelReasoningMode.ENABLED);
        assertThat(snapshot.capabilities()).contains(ModelCapability.REASONING);
        assertThat(snapshot.invocationOptions())
                .containsEntry("thinking_profile", "always")
                .containsEntry("thinking_enabled", true)
                .containsEntry("preserve_thinking", true)
                .containsEntry("requires_reasoning_continuation", true);
    }

    @Test
    void rejectsBailianEndpointThatDoesNotMatchWorkspaceAndRegion() {
        assertThatThrownBy(() -> new CliConfiguration.Model(
                        "aliyun-bailian",
                        "Alibaba Cloud Bailian",
                        "qwen-plus",
                        java.net.URI.create("https://example.com/compatible-mode/v1"),
                        java.net.URI.create("https://example.com/compatible-mode/v1"),
                        "env://DASHSCOPE_API_KEY",
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                        true,
                        "workspace-123",
                        "cn-beijing",
                        "qwen-plus",
                        "Qwen Plus",
                        java.util.Set.of(ModelCapability.TEXT_CHAT),
                        131_072,
                        8_192))
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
    void rejectsRetiredProviderAndBindingVersionFields() throws Exception {
        Path providerConfiguration = Files.createTempFile("haifa-cli-retired-provider", ".yaml");
        Files.writeString(
                providerConfiguration,
                """
                models:
                  default: test-model
                  providers:
                    - id: test
                      endpoint: https://model.example.com/v1
                      credentialRef: env://TEST_KEY
                      nativeStreaming: true
                      dialectId: openai-chat-completions
                      apiBindings:
                        - style: openai-chat-completions
                      models:
                        - id: test-model
                          providerModelId: test-model
                          style: openai-chat-completions
                          capabilities: [TEXT_CHAT]
                          contextWindow: 8192
                          maxOutputTokens: 1024
                """);

        assertThatThrownBy(() -> new CliConfigurationLoader()
                        .load(
                                CliArguments.parse(new String[] {"--config", providerConfiguration.toString()}),
                                Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported model configuration field: dialectId");

        Path bindingConfiguration = Files.createTempFile("haifa-cli-retired-binding", ".yaml");
        Files.writeString(
                bindingConfiguration,
                Files.readString(providerConfiguration)
                        .replace("      dialectId: openai-chat-completions\n", "")
                        .replace(
                                "        - style: openai-chat-completions\n",
                                "        - style: openai-chat-completions\n          styleVersion: '1.0'\n"));

        assertThatThrownBy(() -> new CliConfigurationLoader()
                        .load(
                                CliArguments.parse(new String[] {"--config", bindingConfiguration.toString()}),
                                Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported model configuration field: styleVersion");
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
    void loadsBrowserlessFetchWithSecureDefaults() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli-browserless", ".yaml");
        Files.writeString(
                configuration,
                """
                tools:
                  enabled: [file.read, web.fetch]
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
                  enabled: [file.read, web.fetch]
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
