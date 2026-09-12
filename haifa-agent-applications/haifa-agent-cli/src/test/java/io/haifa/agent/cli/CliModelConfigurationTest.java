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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliModelConfigurationTest {
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
                        "deepseek-v4-flash-vision-exp",
                        "deepseek-responses-v4-flash-vision-exp",
                        "deepseek-anthropic-v4-flash-vision-exp",
                        "gpt-5.6-sol",
                        "gpt-5.6-terra",
                        "gpt-5.6-luna",
                        "gpt-5.3-codex-spark",
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
                        "glm-5.3",
                        "glm-5.3-flash",
                        "glm-5.2",
                        "glm-5.1",
                        "glm-5",
                        "glm-5-turbo",
                        "tokenrhythm-glm-5",
                        "tokenrhythm-glm-5-1",
                        "tokenrhythm-minimax-m2-7",
                        "tokenrhythm-kimi-k2-5",
                        "tokenrhythm-kimi-k2-6",
                        "tokenrhythm-minimax-m2-5",
                        "tokenrhythm-mimo-v2-5-pro",
                        "tokenrhythm-qwen3-7-max",
                        "tokenrhythm-kimi-k2-7-code",
                        "tokenrhythm-glm-5-2",
                        "tokenrhythm-qwen3-8-max",
                        "tokenrhythm-deepseek-v4-flash-0731",
                        "tokenrhythm-seed-2-1-pro",
                        "tokenrhythm-seed-2-1-turbo",
                        "tokenrhythm-deepseek-v4-pro-0813",
                        "tokenrhythm-glm-5-3",
                        "tokenrhythm-qwen3-7-flash",
                        "tokenrhythm-qwen3-8-27b",
                        "tokenrhythm-longcat-2-0",
                        "tokenrhythm-glm-5-3-flash");
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
                .filteredOn(model -> model.providerId().equals("google-antigravity"))
                .extracting(CliConfiguration.Model::id)
                .containsExactly(
                        "antigravity-gemini",
                        "antigravity-gemini-3-8-flash",
                        "antigravity-gemini-3-7-flash",
                        "antigravity-gemini-3-1-pro-preview");
        assertThat(antigravityEnabled.availableModels())
                .filteredOn(model -> model.id().equals("antigravity-gemini-3-8-flash"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.providerId()).isEqualTo("google-antigravity");
                    assertThat(model.modelId()).isEqualTo("gemini-3.8-flash-tiered");
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
                .contains("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.3-codex-spark")
                .doesNotContain("local-openai-responses");
        assertThat(result.approval()).isEqualTo(ApprovalMode.ASK);
        assertThat(result.approvalThreshold()).isEqualTo(CodingApprovalThreshold.LOW);
        assertThat(result.execution().provider()).isEqualTo("host-guarded");
        assertThat(result.persistence().mode()).isEqualTo(ProjectPersistenceMode.SQLITE_WITH_JSONL);
        assertThat(result.persistence().protection()).isEqualTo(ProjectPersistenceProtection.NONE);
        assertThat(result.persistence().databasePath()).contains(database);
        assertThat(result.persistence().transcriptRoot()).contains(transcriptRoot);
        assertThat(result.persistence().protectorReference()).isEmpty();
        assertThat(result.enabledTools())
                .contains("file_read", "file_write", "execution_run")
                .doesNotContain("file_search");
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
}
