package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiResponsesDialectTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Standard Responses dialect validates endpoints and classifies errors")
    void standardDialectValidationAndErrors() {
        OpenAiResponsesDialect dialect = StandardOpenAiResponsesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(ModelApiBindingDefinition.STANDARD_DIALECT);
        assertThat(dialect.version()).isEqualTo("2026-08-31");

        ResolvedModelSnapshot validSnapshot = snapshot(
                "openai-standard", ModelApiBindingDefinition.STANDARD_DIALECT, "https://api.openai.com/v1", Map.of());
        dialect.validateSnapshot(validSnapshot, false);

        ResolvedModelSnapshot insecureSnapshot = snapshot(
                "openai-standard", ModelApiBindingDefinition.STANDARD_DIALECT, "http://api.openai.com/v1", Map.of());
        assertThatThrownBy(() -> dialect.validateSnapshot(insecureSnapshot, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insecure Responses endpoint must be explicitly allowed loopback");

        HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("10")), (k, v) -> true);
        DialectErrorMapping mapping = dialect.classifyError(429, headers, new byte[0], null);
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
        assertThat(mapping.retryable()).isTrue();
        assertThat(mapping.retryAfter()).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("DeepSeek Responses dialect validates endpoint, tool choice, image rejection and sequence number")
    void deepSeekDialectValidationAndFeatures() throws Exception {
        OpenAiResponsesDialect dialect = DeepSeekOpenAiResponsesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(OpenAiResponsesDialects.DEEPSEEK);

        ResolvedModelSnapshot valid = snapshot(
                "deepseek",
                OpenAiResponsesDialects.DEEPSEEK,
                "https://api.deepseek.com",
                Map.of(),
                "deepseek-v4-flash");
        dialect.validateSnapshot(valid, false);

        ResolvedModelSnapshot wrongEndpoint = snapshot(
                "deepseek", OpenAiResponsesDialects.DEEPSEEK, "https://api.other.com", Map.of(), "deepseek-v4-flash");
        assertThatThrownBy(() -> dialect.validateSnapshot(wrongEndpoint, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://api.deepseek.com");

        // Tool choice constraint
        dialect.validateToolChoice("auto");
        assertThatThrownBy(() -> dialect.validateToolChoice("required"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("automatic function selection");

        // Image input rejection
        AgentChatRequest imageRequest = new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                valid,
                List.of(ModelMessage.user(
                        "look", List.of(new ImageUrlPart(URI.create("https://example.com/img.png"))))),
                List.of(),
                100,
                Duration.ofSeconds(30),
                Map.of());
        assertThatThrownBy(() -> dialect.validateRequest(imageRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image input is not verified");

        // Reasoning continuation item
        SensitiveModelReasoning reasoning = SensitiveModelReasoning.of("secret_reasoning");
        ModelMessage assistantMsg = ModelMessage.assistant("output", List.of(), reasoning);
        Optional<Map<String, Object>> reasoningItem = dialect.customizeReasoningInputItem(assistantMsg);
        assertThat(reasoningItem).isPresent();
        assertThat(reasoningItem.get()).containsEntry("type", "reasoning");

        // Event sequence validation
        dialect.validateEventSequence(JSON.readTree("{\"sequence_number\": 1}"));
        assertThatThrownBy(() -> dialect.validateEventSequence(JSON.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing sequence_number");
    }

    @Test
    @DisplayName("Codex Responses dialect decorates headers, accepts blank content-type and parses 429 reset time")
    void codexDialectGoldenFixtureAndErrors() {
        OpenAiResponsesDialect dialect = OpenAiCodexResponsesDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(OpenAiResponsesDialects.OPENAI_CODEX);

        ResolvedModelSnapshot valid = snapshot(
                "openai-codex",
                OpenAiResponsesDialects.OPENAI_CODEX,
                "https://chatgpt.com/backend-api/codex",
                Map.of(
                        OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION, "codex_cli",
                        OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION, "HaifaCodex/1.0"),
                "gpt-5.6-sol",
                "model-auth://openai-codex/user-1");
        dialect.validateSnapshot(valid, false);

        // Header decoration
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://chatgpt.com/backend-api/codex"));
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                valid,
                List.of(ModelMessage.user("hello", List.of())),
                List.of(),
                100,
                Duration.ofSeconds(30),
                Map.of());
        CodexAccountIdentityResolver resolver = ref -> Optional.of(new CodexAccountIdentity("acc-12345"));
        dialect.decorateHeaders(builder, request, "token-secret", resolver);
        HttpRequest req = builder.build();
        assertThat(req.headers().firstValue("Authorization")).contains("Bearer token-secret");
        assertThat(req.headers().firstValue("chatgpt-account-id")).contains("acc-12345");
        assertThat(req.headers().firstValue("originator")).contains("codex_cli");
        assertThat(req.headers().firstValue("User-Agent")).contains("HaifaCodex/1.0");

        // Empty Content-Type tolerance
        assertThat(dialect.allowsEmptyContentType()).isTrue();
        assertThat(StandardOpenAiResponsesDialect.INSTANCE.allowsEmptyContentType())
                .isFalse();

        // 429 Golden error mapping with reset_time
        long futureEpoch = java.time.Instant.now().getEpochSecond() + 15;
        byte[] errorJson = ("{\"error\":{\"code\":\"rate_limit_exceeded\",\"plan_type\":\"plus\",\"resets_at\":"
                        + futureEpoch + "}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = HttpHeaders.of(Map.of(), (k, v) -> true);
        DialectErrorMapping errorMapping = dialect.classifyError(429, headers, errorJson, null);
        assertThat(errorMapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
        assertThat(errorMapping.retryable()).isTrue();
        assertThat(errorMapping.providerCode()).isEqualTo("rate_limit_exceeded");
        assertThat(errorMapping.safeMessage()).contains("plus plan");
        assertThat(errorMapping.retryAfter()).isPresent();
        assertThat(errorMapping.retryAfter().get().toSeconds()).isBetween(10L, 20L);
        // 429 with malicious / oversized plan_type
        byte[] maliciousError =
                "{\"error\":{\"code\":\"rate_limit_exceeded\",\"plan_type\":\"bad<script>evil_plan_name_that_is_way_too_long_and_should_be_dropped\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        DialectErrorMapping sanitizedMapping = dialect.classifyError(429, headers, maliciousError, null);
        assertThat(sanitizedMapping.safeMessage()).isEqualTo("ChatGPT Codex usage limit reached");
    }

    @Test
    @DisplayName("OpenAiResponsesDialects registry fails closed on unknown dialect")
    void registryFailsClosedOnUnknown() {
        ResolvedModelSnapshot unknown = snapshot("openai", "unknown-dialect", "https://api.openai.com", Map.of());
        assertThatThrownBy(() -> OpenAiResponsesDialects.resolve(unknown, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported OpenAI Responses dialect: unknown-dialect");
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String dialect, String endpoint, Map<String, Object> options) {
        return snapshot(provider, dialect, endpoint, options, "gpt-5", "env://TEST_KEY");
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String dialect, String endpoint, Map<String, Object> options, String modelId) {
        return snapshot(provider, dialect, endpoint, options, modelId, "env://TEST_KEY");
    }

    private static ResolvedModelSnapshot snapshot(
            String provider,
            String dialect,
            String endpoint,
            Map<String, Object> options,
            String modelId,
            String credRef) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                modelId,
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                dialect,
                URI.create(endpoint),
                new CredentialRef(credRef),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.IMAGE_URL_INPUT),
                128_000,
                8_192,
                options,
                Map.of());
    }
}
