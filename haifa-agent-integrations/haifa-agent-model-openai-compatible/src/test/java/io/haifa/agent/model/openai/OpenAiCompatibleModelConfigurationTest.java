package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.ApiStyle;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.Dialect;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.ResponseFormat;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.ToolChoice;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesModel;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelConfigurationTest {
    @Test
    void buildsChatAdapterAndDigestProtectedSnapshotFromTypedValues() {
        var configured = standardChat().build();

        assertThat(configured.model()).isInstanceOf(OpenAiCompatibleChatModel.class);
        assertThat(configured.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(configured.snapshot().providerId().value()).isEqualTo("openai");
        assertThat(configured.snapshot().modelId().value()).isEqualTo("primary-chat");
        assertThat(configured.snapshot().providerModelId()).isEqualTo("gpt-example");
        assertThat(configured.snapshot().adapterType()).isEqualTo(ModelApiStyles.OPENAI_CHAT_ADAPTER);
        assertThat(configured.snapshot().apiStyle()).isEqualTo(ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        assertThat(configured.snapshot().dialect()).isEqualTo("standard");
        assertThat(configured.snapshot().endpoint()).isEqualTo(URI.create("https://api.openai.com/v1"));
        assertThat(configured.snapshot().credentialRef().value()).isEqualTo("env://OPENAI_API_KEY");
        assertThat(configured.snapshot().providerOptions())
                .containsEntry(OpenAiCompatibleDialects.ENDPOINT_HOST, "api.openai.com")
                .containsEntry(OpenAiCompatibleModelConfiguration.CONNECT_TIMEOUT_MILLIS, 5_000L)
                .containsEntry(OpenAiCompatibleModelConfiguration.REQUEST_TIMEOUT_MILLIS, 45_000L);
        assertThat(configured.snapshot().invocationOptions())
                .containsEntry("temperature", 0.2d)
                .containsEntry("tool_choice", "required")
                .containsEntry("response_format", java.util.Map.of("type", "json_object"));
        assertThat(configured.snapshot().configurationDigest()).startsWith("sha256:");
        assertThat(standardChat().build().snapshot().configurationDigest())
                .isEqualTo(configured.snapshot().configurationDigest());
    }

    @Test
    void bindsAllImplementedApiStylesToTheirExactAdaptersAndDeepSeekDialects() {
        var chat = deepSeek(ApiStyle.CHAT_COMPLETIONS, "deepseek-v4-pro", URI.create("https://api.deepseek.com"))
                .temperature(0.0d)
                .build();
        var responses = deepSeek(ApiStyle.RESPONSES, "deepseek-v4-flash", URI.create("https://api.deepseek.com"))
                .responseFormat(ResponseFormat.JSON_OBJECT)
                .build();

        assertThat(chat.model()).isInstanceOf(OpenAiCompatibleChatModel.class);
        assertThat(chat.snapshot().dialect()).isEqualTo(OpenAiCompatibleDialects.DEEPSEEK);
        assertThat(responses.model()).isInstanceOf(OpenAiResponsesModel.class);
        assertThat(responses.snapshot().dialect()).isEqualTo(OpenAiResponsesDialects.DEEPSEEK);
        assertThat(chat.snapshot().invocationOptions()).containsEntry("thinking", "disabled");
        assertThat(responses.snapshot().invocationOptions()).containsEntry("thinking", "disabled");
    }

    @Test
    void rejectsUnsafeEndpointsUnsupportedCombinationsAndMissingBounds() {
        assertThatThrownBy(() -> standardChat()
                        .endpoint(URI.create("http://api.openai.com/v1"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> standardChat().tokenLimits(0, 0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token limits");
        assertThatThrownBy(() -> standardChat().temperature(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
        assertThatThrownBy(
                        () -> deepSeek(ApiStyle.RESPONSES, "deepseek-v4-flash", URI.create("https://api.deepseek.com"))
                                .toolChoice(ToolChoice.REQUIRED)
                                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
        assertThatThrownBy(() -> standardChat().requestTimeout(Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeout");
    }

    @Test
    void acceptsOnlyCredentialReferencesAndDoesNotExposeSecretConvenienceMethods() {
        assertThat(OpenAiCompatibleModelConfiguration.Builder.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("apikey")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("secret"));
        assertThat(OpenAiCompatibleModelConfiguration.Builder.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("credentialRef"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes()).containsExactly(CredentialRef.class));
    }

    private static OpenAiCompatibleModelConfiguration.Builder standardChat() {
        return OpenAiCompatibleModelConfiguration.builder(reference -> new ResolvedCredential("test-secret"))
                .providerId("openai")
                .modelId("primary-chat")
                .providerModelId("gpt-example")
                .endpoint(URI.create("https://api.openai.com/v1"))
                .credentialRef(new CredentialRef("env://OPENAI_API_KEY"))
                .capabilities(Set.of(
                        ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT))
                .tokenLimits(128_000, 8_192)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(45))
                .temperature(0.2d)
                .toolChoice(ToolChoice.REQUIRED)
                .responseFormat(ResponseFormat.JSON_OBJECT);
    }

    private static OpenAiCompatibleModelConfiguration.Builder deepSeek(
            ApiStyle style, String providerModelId, URI endpoint) {
        return OpenAiCompatibleModelConfiguration.builder(reference -> new ResolvedCredential("test-secret"))
                .providerId("deepseek")
                .modelId("deepseek-" + style.name().toLowerCase(java.util.Locale.ROOT))
                .providerModelId(providerModelId)
                .apiStyle(style)
                .dialect(Dialect.DEEPSEEK)
                .endpoint(endpoint)
                .credentialRef(new CredentialRef("env://DEEPSEEK_API_KEY"))
                .capabilities(Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING))
                .tokenLimits(1_048_576, 8_192);
    }
}
