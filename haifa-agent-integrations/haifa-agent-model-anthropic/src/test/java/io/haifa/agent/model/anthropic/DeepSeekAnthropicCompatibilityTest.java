package io.haifa.agent.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Provider compatibility contract for documented DeepSeek deviations from Anthropic Messages. */
class DeepSeekAnthropicCompatibilityTest {
    @Test
    void acceptsOnlyExplicitDeepSeekProfilesAtTheCanonicalBindingEndpoint() {
        assertThat(AnthropicMessagesDialects.resolve(deepSeek("deepseek-v4-flash"), false)
                        .id())
                .isEqualTo(AnthropicMessagesDialects.DEEPSEEK);
        assertThat(AnthropicMessagesDialects.resolve(deepSeek("deepseek-v4-pro"), false)
                        .id())
                .isEqualTo(AnthropicMessagesDialects.DEEPSEEK);
    }

    @Test
    void rejectsProviderAutomaticModelMappingBeforeNetworkAccess() {
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(deepSeek("claude-sonnet-alias"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void keepsStandardHttpsProvidersIndependentFromDeepSeekEndpointRules() {
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(
                        snapshot(
                                "deepseek-v4-flash",
                                AnthropicMessagesDialects.DEEPSEEK,
                                URI.create("https://api.deepseek.com")),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://api.deepseek.com/anthropic");

        assertThat(AnthropicMessagesDialects.resolve(
                                snapshot(
                                        "vendor-model",
                                        AnthropicMessagesDialects.STANDARD,
                                        URI.create("https://messages.example.com")),
                                false)
                        .id())
                .isEqualTo(AnthropicMessagesDialects.STANDARD);
    }

    @Test
    void rejectsDeepSeekDialectWithMismatchedProviderId() {
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(
                        snapshot(
                                "fake-deepseek",
                                "deepseek-v4-flash",
                                AnthropicMessagesDialects.DEEPSEEK,
                                URI.create("https://api.deepseek.com/anthropic")),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void acceptsOnlyGlm52AtTheDocumentedZhipuAnthropicEndpoint() {
        assertThat(AnthropicMessagesDialects.resolve(
                                snapshot(
                                        "zhipu",
                                        "glm-5.2",
                                        AnthropicMessagesDialects.ZHIPU,
                                        URI.create("https://open.bigmodel.cn/api/anthropic")),
                                false)
                        .id())
                .isEqualTo(AnthropicMessagesDialects.ZHIPU);
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(
                        snapshot(
                                "zhipu",
                                "glm-5.1",
                                AnthropicMessagesDialects.ZHIPU,
                                URI.create("https://open.bigmodel.cn/api/anthropic")),
                        false))
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(
                        snapshot(
                                "fake-zhipu",
                                "glm-5.2",
                                AnthropicMessagesDialects.ZHIPU,
                                URI.create("https://open.bigmodel.cn/api/anthropic")),
                        false))
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> AnthropicMessagesDialects.resolve(
                        snapshot(
                                "zhipu",
                                "glm-5.2",
                                AnthropicMessagesDialects.ZHIPU,
                                URI.create("https://open.bigmodel.cn/api/paas/v4")),
                        false))
                .hasMessageContaining("api/anthropic");
    }

    private static ResolvedModelSnapshot deepSeek(String providerModelId) {
        return snapshot(
                "deepseek",
                providerModelId,
                AnthropicMessagesDialects.DEEPSEEK,
                URI.create("https://api.deepseek.com/anthropic"));
    }

    private static ResolvedModelSnapshot snapshot(String providerModelId, String dialect, URI endpoint) {
        return snapshot("compatibility-provider", providerModelId, dialect, endpoint);
    }

    private static ResolvedModelSnapshot snapshot(
            String providerId, String providerModelId, String dialect, URI endpoint) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "provider-v1",
                new ModelDefinitionId("compatibility-model"),
                "model-v1",
                providerModelId,
                AnthropicMessagesModel.ADAPTER_TYPE,
                AnthropicMessagesModel.ADAPTER_VERSION,
                ModelApiStyles.ANTHROPIC_MESSAGES,
                dialect,
                endpoint,
                new CredentialRef("env://TEST_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                131_072,
                8_192,
                Map.of(),
                Map.of());
    }
}
