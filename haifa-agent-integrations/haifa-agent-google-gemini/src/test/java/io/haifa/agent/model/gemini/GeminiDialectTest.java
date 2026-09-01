package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
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
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiDialectTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Standard Gemini dialect validates endpoints, builds headers, and classifies quota errors")
    void standardDialectValidationAndFeatures() {
        GeminiDialect dialect = StandardGeminiDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(GeminiDialects.STANDARD);
        assertThat(dialect.version()).isEqualTo("2026-08-31");

        ResolvedModelSnapshot valid = snapshot(
                "google",
                GeminiDialects.STANDARD,
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
                "env://GEMINI_API_KEY");
        dialect.validateSnapshot(valid, false, false);

        ResolvedModelSnapshot invalid = snapshot(
                "google",
                GeminiDialects.STANDARD,
                "https://api.example.com/v1beta",
                "gemini-2.5-flash",
                "env://GEMINI_API_KEY");
        assertThatThrownBy(() -> dialect.validateSnapshot(invalid, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official Gemini binding requires the governed Google HTTPS endpoint");

        AgentChatRequest request = request(valid);
        HttpRequest req = dialect.requestBuilder(request, new ResolvedCredential("test-key"), false)
                .build();
        assertThat(req.headers().firstValue("x-goog-api-key")).contains("test-key");

        // 429 Quota exhausted
        byte[] quotaBody =
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"reason\":\"QUOTA_EXHAUSTED\"}]}}"
                        .getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("60")), (k, v) -> true);
        DialectErrorMapping mapping = dialect.classifyError(429, headers, quotaBody, null);
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
        assertThat(mapping.retryable()).isFalse();
        assertThat(mapping.providerCode()).isEqualTo("quota_exhausted");
        assertThat(mapping.retryAfter()).contains(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Antigravity direct dialect validates CloudCode PA endpoint, wraps request, and unwraps response")
    void antigravityDirectDialectValidationAndEnvelope() throws Exception {
        GeminiDialect dialect = AntigravityDirectGeminiDialect.INSTANCE;
        assertThat(dialect.id()).isEqualTo(GeminiDialects.ANTIGRAVITY_DIRECT);

        ResolvedModelSnapshot valid = snapshot(
                "google-antigravity",
                GeminiDialects.ANTIGRAVITY_DIRECT,
                "https://daily-cloudcode-pa.googleapis.com/v1internal",
                "gemini-3.7-flash",
                "model-auth://google-antigravity/default");
        dialect.validateSnapshot(valid, false, false);

        AgentChatRequest req = request(valid);
        AntigravityCloudCodeProjectResolver resolver = ref -> Optional.of("project-antigravity-123");
        Map<String, Object> innerBody = new LinkedHashMap<>();
        innerBody.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "hello")))));
        innerBody.put("generationConfig", new LinkedHashMap<>(Map.of("maxOutputTokens", 8192, "temperature", 0.7)));

        Map<String, Object> customized = dialect.customizeRequestBody(req, innerBody, resolver);
        assertThat(customized).containsEntry("project", "project-antigravity-123");
        assertThat(customized).containsEntry("model", "gemini-3.7-flash");
        assertThat(customized).containsEntry("userAgent", "antigravity");
        assertThat(customized).containsKey("request");

        @SuppressWarnings("unchecked")
        Map<String, Object> customInner = (Map<String, Object>) customized.get("request");
        assertThat(customInner).containsKey("sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> customGen = (Map<String, Object>) customInner.get("generationConfig");
        assertThat(customGen).doesNotContainKey("maxOutputTokens");

        // Envelope unwrapping
        var envelope = JSON.readTree("{\"response\":{\"candidates\":[]}}");
        var unwrapped = dialect.unwrapResponsePayload(req, envelope);
        assertThat(unwrapped.has("candidates")).isTrue();

        var badEnvelope = JSON.readTree("{\"other\":{}}");
        assertThatThrownBy(() -> dialect.unwrapResponsePayload(req, badEnvelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Antigravity response envelope has no response object");
    }

    private static AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                100,
                Duration.ofSeconds(30),
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String dialect, String endpoint, String modelId, String credRef) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                modelId,
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                dialect,
                URI.create(endpoint),
                new CredentialRef(credRef),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                8_192,
                Map.of(),
                Map.of());
    }
}
