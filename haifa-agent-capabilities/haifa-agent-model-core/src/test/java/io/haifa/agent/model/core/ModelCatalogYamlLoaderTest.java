package io.haifa.agent.model.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCatalogYamlLoaderTest {
    @Test
    void loadsVerifiedBindingsForOpenAiAnthropicAndGeminiStyles() {
        ModelCatalogManifest catalog = loader(resources()).load();

        assertThat(catalog.providers()).hasSize(3);
        assertThat(catalog.binding("openai-chat").orElseThrow().definition().style())
                .isEqualTo(ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        assertThat(catalog.binding("anthropic-messages")
                        .orElseThrow()
                        .definition()
                        .style())
                .isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
        assertThat(catalog.binding("gemini-generate").orElseThrow().definition().style())
                .isEqualTo(ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT);
        assertThat(catalog.binding("openai-chat").orElseThrow().profile().status())
                .isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(catalog.binding("gemini-generate").orElseThrow().definition().capabilities())
                .containsExactlyInAnyOrder(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING);
        assertThat(catalog.digest()).startsWith("sha256:");
    }

    @Test
    void loadsPackagedRepresentativeBindings() {
        ModelCatalogManifest catalog = ModelCatalogYamlLoader.fromClasspath(
                        getClass().getClassLoader(),
                        Map.of(
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                        Set.of(
                                                "deepseek-openai-chat",
                                                "aliyun-bailian-openai-chat",
                                                "siliconflow-openai-chat",
                                                "kimi-openai-chat",
                                                "zhipu-openai-chat"),
                                ModelApiStyles.OPENAI_RESPONSES, Set.of("openai-codex-responses"),
                                ModelApiStyles.ANTHROPIC_MESSAGES, Set.of("deepseek-anthropic-messages"),
                                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT, Set.of("antigravity-direct")),
                        Map.of(
                                new ModelProviderId("deepseek"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("openai-codex"), Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN),
                                new ModelProviderId("aliyun-bailian"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("siliconflow"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("kimi"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("zhipu"), Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("google-antigravity"),
                                        Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN)))
                .load();

        assertThat(catalog.binding("deepseek-chat-pro").orElseThrow().profile().allowedReasoningEfforts())
                .containsExactlyInAnyOrder(
                        io.haifa.agent.model.api.ModelReasoningEffort.HIGH,
                        io.haifa.agent.model.api.ModelReasoningEffort.MAX);
        assertThat(catalog.binding("deepseek-anthropic-pro")
                        .orElseThrow()
                        .profile()
                        .toolReasoningContinuationRequired())
                .isTrue();
        assertThat(catalog.binding("antigravity-gemini").orElseThrow().profile().imageInput())
                .isPresent();
        assertThat(catalog.binding("qwen3-vl-plus").orElseThrow().profile().imageInput())
                .isPresent();
        assertThat(catalog.binding("siliconflow-glm-5-2")
                        .orElseThrow()
                        .profile()
                        .contextWindowTokens())
                .isEqualTo(1_048_576);
        assertThat(catalog.binding("gpt-5.6-sol").orElseThrow().definition().style())
                .isEqualTo(ModelApiStyles.OPENAI_RESPONSES);
    }

    @Test
    void rejectsUnknownBindingField() {
        Map<String, String> resources = resources();
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/bindings/openai-chat.yaml",
                binding("openai-chat", "openai-chat-completions", "standard") + "\nunknown: value\n");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader(resources).load())
                .withMessageContaining("unknown field")
                .withMessageContaining("unknown");
    }

    @Test
    void rejectsUnregisteredDialect() {
        Map<String, String> resources = resources();
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/bindings/openai-chat.yaml",
                binding("openai-chat", "openai-chat-completions", "not-registered"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader(resources).load())
                .withMessageContaining("dialect is not registered");
    }

    @Test
    void rejectsAuthenticationMethodNotRegisteredForProvider() {
        Map<String, String> resources = resources();
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/provider.yaml",
                provider("openai", "openai-chat", "EXTERNAL_LOGIN"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader(resources).load())
                .withMessageContaining("authentication method is not registered");
    }

    @Test
    void rejectsYamlAnchorsAndAliases() {
        Map<String, String> resources = resources();
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/bindings/openai-chat.yaml",
                binding("openai-chat", "openai-chat-completions", "standard")
                        .replace("dialect: standard", "dialect: &registered standard"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader(resources).load())
                .withMessageContaining("YAML anchors and aliases are not allowed");
    }

    @Test
    void rejectsDuplicateBindingIdAcrossProviders() {
        Map<String, String> resources = resources();
        resources.put(
                "META-INF/haifa/model-catalog/providers/anthropic/bindings/anthropic-messages.yaml",
                binding("openai-chat", "anthropic-messages", "standard"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader(resources).load())
                .withMessageContaining("duplicate binding id");
    }

    private static ModelCatalogYamlLoader loader(Map<String, String> resources) {
        return new ModelCatalogYamlLoader(
                resource -> {
                    String value = resources.get(resource);
                    if (value == null) throw new IllegalArgumentException("missing catalog resource: " + resource);
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                },
                Map.of(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, Set.of("standard"),
                        ModelApiStyles.ANTHROPIC_MESSAGES, Set.of("standard"),
                        ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT, Set.of("standard")),
                Map.of(
                        new ModelProviderId("openai"), Set.of(ModelAuthenticationMethod.API_KEY),
                        new ModelProviderId("anthropic"), Set.of(ModelAuthenticationMethod.API_KEY),
                        new ModelProviderId("gemini"), Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN)));
    }

    private static Map<String, String> resources() {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put(
                "META-INF/haifa/model-catalog/catalog.yaml",
                """
                schemaVersion: haifa.model-catalog/v1
                providers:
                  - resource: providers/openai/provider.yaml
                  - resource: providers/anthropic/provider.yaml
                  - resource: providers/gemini/provider.yaml
                """);
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/provider.yaml",
                provider("openai", "openai-chat", "API_KEY"));
        resources.put(
                "META-INF/haifa/model-catalog/providers/anthropic/provider.yaml",
                provider("anthropic", "anthropic-messages", "API_KEY"));
        resources.put(
                "META-INF/haifa/model-catalog/providers/gemini/provider.yaml",
                provider("gemini", "gemini-generate", "EXTERNAL_LOGIN"));
        resources.put(
                "META-INF/haifa/model-catalog/providers/openai/bindings/openai-chat.yaml",
                binding("openai-chat", "openai-chat-completions", "standard"));
        resources.put(
                "META-INF/haifa/model-catalog/providers/anthropic/bindings/anthropic-messages.yaml",
                binding("anthropic-messages", "anthropic-messages", "standard"));
        resources.put(
                "META-INF/haifa/model-catalog/providers/gemini/bindings/gemini-generate.yaml",
                binding("gemini-generate", "google-gemini-generate-content", "standard"));
        return resources;
    }

    private static String provider(String providerId, String bindingId, String authenticationMethod) {
        return """
                schemaVersion: haifa.model-catalog-provider/v1
                providerId: %s
                version: "1.0"
                displayName: %s Provider
                status: ACTIVE
                authenticationMethods: [%s]
                bindings:
                  - resource: bindings/%s.yaml
                """
                .formatted(providerId, providerId, authenticationMethod, bindingId);
    }

    private static String binding(String bindingId, String apiStyle, String dialect) {
        return """
                schemaVersion: haifa.model-catalog-binding/v1
                bindingId: %s
                version: "1.0"
                providerModelId: %s-model
                displayName: %s display
                status: ACTIVE
                apiStyle: %s
                dialect: %s
                capabilities: [TEXT_CHAT, TOOL_CALLING]
                profile:
                  version: "2.0"
                  reasoningBehavior: NONE
                  allowedReasoningModes: [DISABLED]
                  allowedReasoningEfforts: []
                  maximumReasoningTokens: null
                  minimumOutputTokens: 1
                  maximumOutputTokens: 4096
                  contextWindowTokens: 8192
                  toolReasoningContinuationRequired: false
                  nativeStreaming: true
                  usageStreaming: true
                  reasoningStreaming: false
                  partialOutputFailureBehavior: NON_RETRYABLE
                  inputModalities: [TEXT]
                  outputModalities: [TEXT]
                  imageInput: null
                  status: VERIFIED
                  lastVerifiedOn: "2026-09-03"
                """
                .formatted(bindingId, bindingId, bindingId, apiStyle, dialect);
    }
}
