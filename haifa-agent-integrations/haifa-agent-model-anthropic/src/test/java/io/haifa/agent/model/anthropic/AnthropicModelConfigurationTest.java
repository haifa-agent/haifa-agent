package io.haifa.agent.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnthropicModelConfigurationTest {
    @Test
    void buildsValidDeepSeekAnthropicConfiguration() {
        AnthropicModelConfiguration configuration = deepSeek()
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(45))
                .toolChoice(AnthropicModelConfiguration.ToolChoice.REQUIRED)
                .build();

        ResolvedModelSnapshot snapshot = configuration.snapshot();
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
        assertThat(snapshot.dialect()).isEqualTo(AnthropicMessagesDialects.DEEPSEEK);
        assertThat(snapshot.adapterType()).isEqualTo(AnthropicMessagesModel.ADAPTER_TYPE);
        assertThat(snapshot.adapterVersion()).isEqualTo(AnthropicMessagesModel.ADAPTER_VERSION);
        assertThat(snapshot.providerOptions())
                .containsEntry(AnthropicModelConfiguration.CONNECT_TIMEOUT_MILLIS, 5_000L)
                .containsEntry(AnthropicModelConfiguration.REQUEST_TIMEOUT_MILLIS, 45_000L);
        assertThat(snapshot.invocationOptions())
                .containsEntry("thinking", "disabled")
                .containsEntry("tool_choice", "any");
        assertThat(configuration.model()).isInstanceOf(AnthropicMessagesModel.class);
    }

    @Test
    void buildsValidZhipuAnthropicConfiguration() {
        AnthropicModelConfiguration configuration = AnthropicModelConfiguration.builder(
                        reference -> new ResolvedCredential("test-secret"))
                .providerId("zhipu")
                .modelId("zhipu-glm-5.2-anthropic")
                .providerModelId("glm-5.2")
                .dialect(AnthropicModelConfiguration.Dialect.ZHIPU)
                .endpoint(URI.create("https://open.bigmodel.cn/api/anthropic"))
                .credentialRef(new CredentialRef("env://ZHIPU_API_KEY"))
                .capabilities(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))
                .tokenLimits(131_072, 8_192)
                .toolChoice(AnthropicModelConfiguration.ToolChoice.AUTO)
                .build();

        ResolvedModelSnapshot snapshot = configuration.snapshot();
        assertThat(snapshot.apiStyle()).isEqualTo(ModelApiStyles.ANTHROPIC_MESSAGES);
        assertThat(snapshot.dialect()).isEqualTo(AnthropicMessagesDialects.ZHIPU);
        assertThat(snapshot.invocationOptions()).containsEntry("tool_choice", "auto");
    }

    @Test
    void buildsValidStandardConfiguration() {
        AnthropicModelConfiguration configuration = AnthropicModelConfiguration.builder(
                        reference -> new ResolvedCredential("test-secret"))
                .providerId("anthropic")
                .modelId("claude-sonnet")
                .providerModelId("claude-3-5-sonnet")
                .dialect(AnthropicModelConfiguration.Dialect.STANDARD)
                .endpoint(URI.create("https://api.anthropic.com"))
                .credentialRef(new CredentialRef("env://ANTHROPIC_API_KEY"))
                .capabilities(Set.of(ModelCapability.TEXT_CHAT))
                .tokenLimits(200_000, 4_096)
                .build();

        assertThat(configuration.snapshot().dialect()).isEqualTo("standard");
    }

    @Test
    void rejectsUnverifiedDeepSeekAnthropicModel() {
        assertThatThrownBy(() -> deepSeek().providerModelId("unverified-model").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek Anthropic model profile is not verified");
    }

    @Test
    void rejectsInvalidDeepSeekEndpointHost() {
        assertThatThrownBy(() -> deepSeek()
                        .endpoint(URI.create("https://api.wrong-host.com/anthropic"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek endpoint host must be api.deepseek.com");
    }

    @Test
    void rejectsInvalidDeepSeekEndpointPath() {
        assertThatThrownBy(() -> deepSeek()
                        .endpoint(URI.create("https://api.deepseek.com/v1"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek endpoint path is invalid for the selected API style");
    }

    @Test
    void rejectsUnverifiedZhipuAnthropicModel() {
        assertThatThrownBy(() -> AnthropicModelConfiguration.builder(reference -> new ResolvedCredential("test-secret"))
                        .providerId("zhipu")
                        .modelId("zhipu-glm-5-anthropic")
                        .providerModelId("glm-5")
                        .dialect(AnthropicModelConfiguration.Dialect.ZHIPU)
                        .endpoint(URI.create("https://open.bigmodel.cn/api/anthropic"))
                        .credentialRef(new CredentialRef("env://ZHIPU_API_KEY"))
                        .capabilities(Set.of(ModelCapability.TEXT_CHAT))
                        .tokenLimits(131_072, 8_192)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zhipu Anthropic model profile is not verified");
    }

    @Test
    void rejectsInvalidTokenLimits() {
        assertThatThrownBy(() -> deepSeek().tokenLimits(100, 200).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token limits must be positive and output must fit the context");
    }

    @Test
    void rejectsMissingTextChatCapability() {
        assertThatThrownBy(() -> deepSeek()
                        .capabilities(Set.of(ModelCapability.TOOL_CALLING))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilities must include TEXT_CHAT");
    }

    @Test
    void rejectsToolChoiceWithoutToolCallingCapability() {
        assertThatThrownBy(() -> deepSeek()
                        .capabilities(Set.of(ModelCapability.TEXT_CHAT))
                        .toolChoice(AnthropicModelConfiguration.ToolChoice.AUTO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolChoice requires TOOL_CALLING capability");
    }

    private static AnthropicModelConfiguration.Builder deepSeek() {
        return AnthropicModelConfiguration.builder(reference -> new ResolvedCredential("test-secret"))
                .providerId("deepseek")
                .modelId("deepseek-anthropic")
                .providerModelId("deepseek-v4-flash")
                .dialect(AnthropicModelConfiguration.Dialect.DEEPSEEK)
                .endpoint(URI.create("https://api.deepseek.com/anthropic"))
                .credentialRef(new CredentialRef("env://DEEPSEEK_API_KEY"))
                .capabilities(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))
                .tokenLimits(1_048_576, 8_192);
    }
}
