package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .hasMessageContaining("toolCallId");
        ModelMessage message = new ModelMessage(ModelMessageRole.TOOL, "result", List.of(), "call-1");
        assertThat(message.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void providerOptionsAreDeeplyImmutable() {
        List<Object> nested = new ArrayList<>(List.of("disabled"));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("thinking", Map.of("modes", nested));
        ModelProviderDefinition provider = new ModelProviderDefinition(
                new ModelProviderId("deepseek"),
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

    private static ModelProviderDefinition provider(ModelProviderId id, List<ModelDefinition> models) {
        return new ModelProviderDefinition(
                id,
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
