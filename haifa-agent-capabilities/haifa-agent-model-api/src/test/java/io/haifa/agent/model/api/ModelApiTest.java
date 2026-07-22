package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelApiTest {
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
    void providerOptionsAreDeeplyImmutable() {
        List<Object> nested = new ArrayList<>(List.of("disabled"));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("thinking", Map.of("modes", nested));
        ModelProviderDefinition provider = new ModelProviderDefinition(
                new ModelProviderId("deepseek"),
                "provider-v1",
                "DeepSeek",
                "openai-compatible",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                ProviderStatus.ACTIVE,
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
    void frozenSnapshotDigestCoversEndpointLimitsVersionsAndOptions() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "provider-v1",
                new ModelDefinitionId("deepseek-v4-pro"),
                "model-v1",
                "deepseek-v4-pro",
                "openai-compatible",
                "adapter-v1",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
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
                        URI.create("https://changed.example.com"),
                        snapshot.credentialRef(),
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
                                snapshot.endpoint(),
                                snapshot.credentialRef(),
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
                "openai-compatible",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                ProviderStatus.ACTIVE,
                models,
                Map.of("thinking", "disabled"),
                Map.of());
    }

    private static ModelDefinition model(ModelProviderId providerId, String id, String providerModelId) {
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
                Map.of());
    }
}
