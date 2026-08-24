package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelApiTest {
    @Test
    void modelFailureProjectsUntrustedRetryDelayWithoutOverflow() {
        ModelInvocationException failure = new ModelInvocationException(
                ModelErrorCategory.RATE_LIMITED,
                true,
                429,
                "rate_limited",
                new ModelCallId("call-1"),
                "safe failure",
                null,
                Duration.ofSeconds(Long.MAX_VALUE),
                false);

        assertThat(failure.retryAfterMillis()).hasValue(Long.MAX_VALUE);
        assertThat(failure.toString()).doesNotContain("credential", "prompt", "response");
    }

    @Test
    void providerDefensivelyCopiesOrderedModelsAndRejectsDuplicates() {
        ModelProviderId providerId = new ModelProviderId("deepseek");
        List<ModelDefinition> source = new ArrayList<>();
        source.add(model(providerId, "deepseek-v4-pro", "deepseek-v4-pro"));
        ModelProviderDefinition provider = provider(providerId, source);

        source.clear();

        assertThat(provider.models()).extracting(value -> value.id().value()).containsExactly("deepseek-v4-pro");
        assertThatThrownBy(() -> provider.models().add(model(providerId, "other", "other")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider(
                        providerId,
                        List.of(
                                model(providerId, "deepseek-v4-pro", "deepseek-v4-pro"),
                                model(providerId, "deepseek-v4-pro", "deepseek-v4-pro-2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate model id");
    }

    @Test
    void credentialStringRepresentationNeverContainsSecret() {
        ResolvedCredential credential = new ResolvedCredential("test-super-secret");

        assertThat(credential.value()).isEqualTo("test-super-secret");
        assertThat(credential.toString())
                .isEqualTo("ResolvedCredential[REDACTED]")
                .doesNotContain("test-super-secret");
    }

    @Test
    void modelMessagesEnforceToolCorrelation() {
        assertThatThrownBy(() -> ModelMessage.text(ModelMessageRole.TOOL, "result"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerCorrelationId");
        List<Object> nested = new ArrayList<>(List.of("value"));
        ModelMessage message = ModelMessage.tool(
                new ProviderToolCallCorrelationId("call-1"), "result", Map.of("nested", nested), true);
        nested.add("changed");
        assertThat(message.providerCorrelationId().orElseThrow().value()).isEqualTo("call-1");
        assertThat(message.toolResultData()).containsEntry("nested", List.of("value"));
        assertThat(message.toolResultTruncated()).isTrue();
        assertThatThrownBy(() -> message.toolResultData().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ModelMessage(
                        ModelMessageRole.USER,
                        "result",
                        List.of(),
                        java.util.Optional.empty(),
                        Map.of("unexpected", true),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only tool messages");
    }

    @Test
    void imagePartsAreBoundedTypedAndRedacted() {
        ImageUrlPart remote = new ImageUrlPart(URI.create("https://images.example.com/cat.png?token=private"));
        byte[] source = {(byte) 0x89, 0x50, 0x4e, 0x47};
        ImageDataPart uploaded = new ImageDataPart("image/png", source);
        ModelMessage message = ModelMessage.user("describe both images", List.of(remote, uploaded));

        source[0] = 0;

        assertThat(message.images()).containsExactly(remote, uploaded);
        assertThat(uploaded.bytes()).containsExactly((byte) 0x89, 0x50, 0x4e, 0x47);
        assertThat(remote.toString()).doesNotContain("token=private", "/cat.png");
        assertThat(uploaded.toString()).doesNotContain("iVBOR");
        assertThatThrownBy(() -> new ImageUrlPart(URI.create("file:///tmp/cat.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> ModelMessage.user("too many", List.of(remote, remote, remote, remote, remote)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 4");
        assertThatThrownBy(() -> new ModelMessage(
                        ModelMessageRole.ASSISTANT,
                        "not allowed",
                        List.of(),
                        java.util.Optional.empty(),
                        Map.of(),
                        false,
                        java.util.Optional.empty(),
                        List.of(remote)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user messages");
    }

    @Test
    void providerOptionsAreDeeplyImmutable() {
        List<Object> nested = new ArrayList<>(List.of("disabled"));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("thinking", Map.of("modes", nested));
        ModelProviderDefinition provider = new ModelProviderDefinition(
                new ModelProviderId("deepseek"),
                "provider-v1",
                "DeepSeek",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, "deepseek-openai-chat")),
                List.of(model(new ModelProviderId("deepseek"), "deepseek-v4-pro", "deepseek-v4-pro")),
                options,
                Map.of());

        nested.add("enabled");
        Map<?, ?> thinking = (Map<?, ?>) provider.options().get("thinking");
        List<?> modes = (List<?>) thinking.get("modes");

        assertThat(modes).hasSize(1);
        assertThat(modes.getFirst()).isEqualTo("disabled");
        assertThatThrownBy(modes::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void providerSharesConnectionWhileBindingsOwnDialectAndEndpointOverride() {
        ModelProviderId providerId = new ModelProviderId("multi-style");
        ModelDefinition chat =
                model(providerId, "chat-model", "same-provider-model", ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        ModelDefinition responses =
                model(providerId, "responses-model", "same-provider-model", ModelApiStyles.OPENAI_RESPONSES);
        ModelDefinition anthropic =
                model(providerId, "anthropic-model", "same-provider-model", ModelApiStyles.ANTHROPIC_MESSAGES);
        URI providerEndpoint = URI.create("https://model.example.com/v1");
        URI responsesEndpoint = URI.create("https://responses.example.com/v1");

        ModelProviderDefinition provider = new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "Multi Style",
                providerEndpoint,
                new CredentialRef("env://MODEL_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(
                        new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS),
                        new ModelApiBindingDefinition(
                                ModelApiStyles.OPENAI_RESPONSES, "vendor-responses", responsesEndpoint),
                        new ModelApiBindingDefinition(
                                ModelApiStyles.ANTHROPIC_MESSAGES,
                                "vendor-anthropic",
                                URI.create("https://messages.example.com"))),
                List.of(chat, responses, anthropic),
                Map.of(),
                Map.of());

        assertThat(provider.endpoint()).isEqualTo(providerEndpoint);
        assertThat(provider.credentialRef().value()).isEqualTo("env://MODEL_API_KEY");
        assertThat(provider.nativeStreaming()).isTrue();
        assertThat(provider.binding(ModelApiStyles.OPENAI_CHAT_COMPLETIONS).dialect())
                .isEqualTo(ModelApiBindingDefinition.STANDARD_DIALECT);
        assertThat(provider.binding(ModelApiStyles.OPENAI_CHAT_COMPLETIONS).resolveEndpoint(provider.endpoint()))
                .isEqualTo(providerEndpoint);
        assertThat(provider.binding(ModelApiStyles.OPENAI_RESPONSES).resolveEndpoint(provider.endpoint()))
                .isEqualTo(responsesEndpoint);
        assertThat(ModelApiStyles.adapterType(ModelApiStyles.ANTHROPIC_MESSAGES))
                .isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER);
        assertThat(ModelApiStyles.adapterType(ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT))
                .isEqualTo(ModelApiStyles.GOOGLE_GEMINI_ADAPTER);
        assertThat(provider.models())
                .extracting(ModelDefinition::providerModelId)
                .containsExactly("same-provider-model", "same-provider-model", "same-provider-model");

        assertThatThrownBy(() -> new ModelProviderDefinition(
                        providerId,
                        "provider-v1",
                        "Invalid",
                        providerEndpoint,
                        new CredentialRef("env://MODEL_API_KEY"),
                        true,
                        ProviderStatus.ACTIVE,
                        provider.apiBindings(),
                        List.of(
                                chat,
                                model(
                                        providerId,
                                        "duplicate-chat-model",
                                        "same-provider-model",
                                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS)),
                        Map.of(),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate provider model id within API style");
    }

    @Test
    void frozenSnapshotDigestCoversEndpointLimitsVersionsAndOptions() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "provider-v1",
                new ModelDefinitionId("deepseek-v4-pro"),
                "model-v1",
                "deepseek-v4-pro",
                "openai-compatible",
                "adapter-v1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "deepseek-openai-chat",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT),
                1_048_576,
                8_192,
                Map.of("transport", Map.of("timeout", 30)),
                Map.of("thinking", "disabled"));

        assertThat(snapshot.configurationDigest()).startsWith("sha256:");
        assertThatThrownBy(() -> new ResolvedModelSnapshot(
                        snapshot.schemaVersion(),
                        snapshot.providerId(),
                        snapshot.providerVersion(),
                        snapshot.modelId(),
                        snapshot.modelVersion(),
                        snapshot.providerModelId(),
                        snapshot.adapterType(),
                        snapshot.adapterVersion(),
                        snapshot.apiStyle(),
                        snapshot.dialect(),
                        URI.create("https://changed.example.com"),
                        snapshot.credentialRef(),
                        snapshot.nativeStreaming(),
                        snapshot.capabilities(),
                        snapshot.contextWindow(),
                        snapshot.maxOutputTokens(),
                        snapshot.providerOptions(),
                        snapshot.invocationOptions(),
                        snapshot.configurationDigest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
        assertThat(ResolvedModelSnapshot.create(
                                snapshot.providerId(),
                                snapshot.providerVersion(),
                                snapshot.modelId(),
                                snapshot.modelVersion(),
                                snapshot.providerModelId(),
                                snapshot.adapterType(),
                                snapshot.adapterVersion(),
                                snapshot.apiStyle(),
                                snapshot.dialect(),
                                snapshot.endpoint(),
                                snapshot.credentialRef(),
                                snapshot.nativeStreaming(),
                                snapshot.capabilities(),
                                snapshot.contextWindow(),
                                snapshot.maxOutputTokens() - 1,
                                snapshot.providerOptions(),
                                snapshot.invocationOptions())
                        .configurationDigest())
                .isNotEqualTo(snapshot.configurationDigest());
    }

    private static ModelProviderDefinition provider(ModelProviderId id, List<ModelDefinition> models) {
        return new ModelProviderDefinition(
                id,
                "provider-v1",
                "DeepSeek",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, "deepseek-openai-chat")),
                models,
                Map.of("thinking", "disabled"),
                Map.of());
    }

    private static ModelDefinition model(ModelProviderId providerId, String id, String providerModelId) {
        return model(providerId, id, providerModelId, ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
    }

    private static ModelDefinition model(
            ModelProviderId providerId, String id, String providerModelId, ApiStyleId style) {
        return new ModelDefinition(
                new ModelDefinitionId(id),
                "model-v1",
                providerId,
                providerModelId,
                id,
                ModelStatus.ACTIVE,
                EnumSet.allOf(ModelCapability.class),
                1_048_576,
                393_216,
                Map.of("thinking", "disabled"),
                Map.of(),
                style);
    }
}
