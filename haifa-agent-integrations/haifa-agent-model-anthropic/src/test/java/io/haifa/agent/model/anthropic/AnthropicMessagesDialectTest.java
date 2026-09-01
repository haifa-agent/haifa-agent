package io.haifa.agent.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnthropicMessagesDialectTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Standard Anthropic dialect validates endpoints, configures thinking, and classifies errors")
    void standardDialectFeatures() {
        AnthropicMessagesDialect dialect = StandardAnthropicMessagesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(AnthropicMessagesDialects.STANDARD);
        assertThat(dialect.version()).isEqualTo("2026-08-31");

        ResolvedModelSnapshot valid = snapshot(
                "anthropic-standard",
                AnthropicMessagesDialects.STANDARD,
                "https://api.anthropic.com/v1",
                Map.of(),
                "claude-3-5-sonnet");
        dialect.validateSnapshot(valid, false);

        ResolvedModelSnapshot insecure = snapshot(
                "anthropic-standard",
                AnthropicMessagesDialects.STANDARD,
                "http://api.anthropic.com/v1",
                Map.of(),
                "claude-3-5-sonnet");
        assertThatThrownBy(() -> dialect.validateSnapshot(insecure, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insecure Anthropic Messages endpoint must be explicitly allowed loopback");

        Map<String, Object> body = new LinkedHashMap<>();
        dialect.configureThinking(
                body, Map.of("thinking", "enabled", "reasoning_token_budget", 2048L, "reasoning_effort", "high"));
        assertThat(body).containsKey("thinking");
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled", "budget_tokens", 2048L));
        assertThat(body.get("output_config")).isEqualTo(Map.of("effort", "high"));

        HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("12")), (k, v) -> true);
        DialectErrorMapping mapping = dialect.classifyError(429, headers, new byte[0], null);
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
        assertThat(mapping.retryable()).isTrue();
        assertThat(mapping.retryAfter()).contains(Duration.ofSeconds(12));
    }

    @Test
    @DisplayName("DeepSeek Anthropic dialect rejects structured output and redacted thinking")
    void deepSeekDialectValidationAndConstraints() throws Exception {
        AnthropicMessagesDialect dialect = DeepSeekAnthropicMessagesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(AnthropicMessagesDialects.DEEPSEEK);

        ResolvedModelSnapshot valid = snapshot(
                "deepseek",
                AnthropicMessagesDialects.DEEPSEEK,
                "https://api.deepseek.com/anthropic",
                Map.of(),
                "deepseek-v4-flash");
        dialect.validateSnapshot(valid, false);

        AgentChatRequest structuredRequest = new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                valid,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                100,
                Duration.ofSeconds(30),
                Map.of(),
                Optional.of(
                        new StructuredOutputRequirement("schema1", "v1", "responseName", Map.of("type", "object"))));
        assertThatThrownBy(() -> dialect.validateRequest(structuredRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek Anthropic Messages structured output is not verified");

        assertThatThrownBy(() -> dialect.validateContentBlock(
                        "redacted_thinking", JSON.readTree("{\"type\":\"redacted_thinking\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek returned unsupported redacted thinking");
    }

    @Test
    @DisplayName("Zhipu Anthropic dialect supports adaptive thinking mode")
    void zhipuDialectAdaptiveThinking() {
        AnthropicMessagesDialect dialect = ZhipuAnthropicMessagesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(AnthropicMessagesDialects.ZHIPU);

        ResolvedModelSnapshot valid = snapshot(
                "zhipu",
                AnthropicMessagesDialects.ZHIPU,
                "https://open.bigmodel.cn/api/anthropic",
                Map.of(),
                "glm-5.2");
        dialect.validateSnapshot(valid, false);

        Map<String, Object> body = new LinkedHashMap<>();
        dialect.configureThinking(body, Map.of("thinking", "adaptive", "reasoning_effort", "high"));
        assertThat(body).containsKey("thinking");
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(body.get("output_config")).isEqualTo(Map.of("effort", "high"));
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String dialect, String endpoint, Map<String, Object> options, String modelId) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                modelId,
                AnthropicMessagesModel.ADAPTER_TYPE,
                AnthropicMessagesModel.ADAPTER_VERSION,
                ModelApiStyles.ANTHROPIC_MESSAGES,
                dialect,
                URI.create(endpoint),
                new CredentialRef("env://TEST_KEY"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                128_000,
                8_192,
                options,
                Map.of());
    }
}
