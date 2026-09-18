package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelHttpErrorClassifierTest {

    private static HttpHeaders headers(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (k, v) -> true);
    }

    private static HttpHeaders emptyHeaders() {
        return headers(Map.of());
    }

    @Test
    void classify402StrictHandling() {
        byte[] jsonBody =
                "{\"error\":{\"message\":\"You must add funds to your wallet\",\"code\":\"insufficient_balance\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = headers(Map.of("retry-after", List.of("120"), "x-request-id", List.of("req-402")));

        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(
                402,
                headers,
                jsonBody,
                null,
                (status, h, body, def) -> new ModelErrorMapping(
                        ModelErrorCategory.RATE_LIMITED,
                        true,
                        status,
                        "custom_code",
                        "custom message",
                        Optional.of(Duration.ofSeconds(30)),
                        Optional.empty()));

        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.PAYMENT_REQUIRED);
        assertThat(mapping.retryable()).isFalse();
        assertThat(mapping.retryAfter()).isEmpty();
        assertThat(mapping.safeMessage()).isEqualTo(ModelHttpErrorClassifier.PAYMENT_REQUIRED_SAFE_PROMPT);
        assertThat(mapping.httpStatus()).isEqualTo(402);
        assertThat(mapping.providerCode()).isEqualTo("insufficient_balance");
        assertThat(mapping.providerRequestId()).hasValue("req-402");
    }

    @Test
    void classify402WithEmptyBodyAndNoHeaders() {
        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(402, emptyHeaders(), new byte[0], null);

        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.PAYMENT_REQUIRED);
        assertThat(mapping.retryable()).isFalse();
        assertThat(mapping.retryAfter()).isEmpty();
        assertThat(mapping.safeMessage()).isEqualTo(ModelHttpErrorClassifier.PAYMENT_REQUIRED_SAFE_PROMPT);
        assertThat(mapping.httpStatus()).isEqualTo(402);
        assertThat(mapping.providerCode()).isEqualTo("http_402");
        assertThat(mapping.providerRequestId()).isEmpty();
    }

    @Test
    void classifyDeterministic4xxErrors() {
        // 400
        ModelErrorMapping m400 = ModelHttpErrorClassifier.classify(400, emptyHeaders(), null, null);
        assertThat(m400.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
        assertThat(m400.retryable()).isFalse();

        // 401
        ModelErrorMapping m401 = ModelHttpErrorClassifier.classify(401, emptyHeaders(), null, null);
        assertThat(m401.category()).isEqualTo(ModelErrorCategory.AUTHENTICATION_FAILED);
        assertThat(m401.retryable()).isFalse();

        // 403
        ModelErrorMapping m403 = ModelHttpErrorClassifier.classify(403, emptyHeaders(), null, null);
        assertThat(m403.category()).isEqualTo(ModelErrorCategory.PERMISSION_DENIED);
        assertThat(m403.retryable()).isFalse();

        // 404
        ModelErrorMapping m404 = ModelHttpErrorClassifier.classify(404, emptyHeaders(), null, null);
        assertThat(m404.category()).isEqualTo(ModelErrorCategory.MODEL_NOT_FOUND);
        assertThat(m404.retryable()).isFalse();

        // 413
        ModelErrorMapping m413 = ModelHttpErrorClassifier.classify(413, emptyHeaders(), null, null);
        assertThat(m413.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
        assertThat(m413.retryable()).isFalse();

        // 422
        ModelErrorMapping m422 = ModelHttpErrorClassifier.classify(422, emptyHeaders(), null, null);
        assertThat(m422.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
        assertThat(m422.retryable()).isFalse();

        // Unrecognized 4xx (e.g. 418, 405)
        ModelErrorMapping m418 = ModelHttpErrorClassifier.classify(418, emptyHeaders(), null, null);
        assertThat(m418.category()).isEqualTo(ModelErrorCategory.UNKNOWN_PROVIDER_ERROR);
        assertThat(m418.retryable()).isFalse();
    }

    @Test
    void classifyTransientErrorsWithRetryAndBackoff() {
        // 408
        ModelErrorMapping m408 = ModelHttpErrorClassifier.classify(408, emptyHeaders(), null, null);
        assertThat(m408.category()).isEqualTo(ModelErrorCategory.TIMEOUT);
        assertThat(m408.retryable()).isTrue();

        // 429 with Retry-After
        HttpHeaders h429 = headers(Map.of("retry-after", List.of("5")));
        ModelErrorMapping m429 = ModelHttpErrorClassifier.classify(429, h429, null, null);
        assertThat(m429.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
        assertThat(m429.retryable()).isTrue();
        assertThat(m429.retryAfter()).hasValue(Duration.ofSeconds(5));

        // 500, 502, 503
        for (int code : List.of(500, 502, 503, 529, 599)) {
            ModelErrorMapping mServer = ModelHttpErrorClassifier.classify(code, emptyHeaders(), null, null);
            assertThat(mServer.category()).isEqualTo(ModelErrorCategory.SERVER_ERROR);
            assertThat(mServer.retryable()).isTrue();
        }

        // 504
        ModelErrorMapping m504 = ModelHttpErrorClassifier.classify(504, emptyHeaders(), null, null);
        assertThat(m504.category()).isEqualTo(ModelErrorCategory.TIMEOUT);
        assertThat(m504.retryable()).isTrue();
    }

    @Test
    void parseOpenAiErrorPayload() {
        byte[] body = ("{\"error\": {\"message\": \"Rate limit reached for requests\", "
                        + "\"type\": \"requests\", \"code\": \"rate_limit_exceeded\"}}")
                .getBytes(StandardCharsets.UTF_8);
        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(429, emptyHeaders(), body, null);

        assertThat(mapping.providerCode()).isEqualTo("rate_limit_exceeded");
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
    }

    @Test
    void parseAnthropicErrorPayload() {
        byte[] body = ("{\"type\": \"error\", \"error\": {\"type\": \"overloaded_error\", "
                        + "\"message\": \"Anthropic's API is temporarily overloaded\"}}")
                .getBytes(StandardCharsets.UTF_8);
        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(529, emptyHeaders(), body, null);

        assertThat(mapping.providerCode()).isEqualTo("overloaded_error");
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.SERVER_ERROR);
    }

    @Test
    void parseGeminiErrorPayloadWithReasons() {
        byte[] body = ("{\"error\": {\"code\": 429, \"message\": \"Resource has been exhausted\", "
                        + "\"status\": \"RESOURCE_EXHAUSTED\", \"details\": [{\"reason\": \"QUOTA_EXHAUSTED\"}]}}")
                .getBytes(StandardCharsets.UTF_8);

        ModelErrorMapping mapping =
                ModelHttpErrorClassifier.classify(429, emptyHeaders(), body, null, (status, h, parsed, def) -> {
                    if (parsed.reasons().contains("QUOTA_EXHAUSTED")) {
                        return new ModelErrorMapping(
                                ModelErrorCategory.RATE_LIMITED,
                                false,
                                status,
                                "quota_exhausted",
                                def.safeMessage(),
                                def.retryAfter(),
                                def.providerRequestId());
                    }
                    return def;
                });

        assertThat(mapping.providerCode()).isEqualTo("quota_exhausted");
        assertThat(mapping.retryable()).isFalse();
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
    }

    @Test
    void fallbackOnHtmlOrPlainText() {
        byte[] htmlBody =
                "<!DOCTYPE html><html><body>Error 502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(502, emptyHeaders(), htmlBody, null);

        assertThat(mapping.providerCode()).isEqualTo("http_502");
        assertThat(mapping.category()).isEqualTo(ModelErrorCategory.SERVER_ERROR);

        byte[] textBody = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
        ModelErrorMapping textMapping = ModelHttpErrorClassifier.classify(500, emptyHeaders(), textBody, null);
        assertThat(textMapping.providerCode()).isEqualTo("http_500");
    }

    @Test
    void redactsSensitiveCredentialFromProviderCode() {
        String secretKey = "sk-live-secret12345678";
        byte[] body = ("{\"error\": {\"code\": \"invalid_key_" + secretKey + "\", \"message\": \"bad key\"}}")
                .getBytes(StandardCharsets.UTF_8);

        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(401, emptyHeaders(), body, secretKey);

        assertThat(mapping.providerCode()).doesNotContain(secretKey);
        assertThat(mapping.providerCode()).contains("[redacted]");
    }

    @Test
    void requestIdResolutionFromHeaderAndBody() {
        // From header
        HttpHeaders h = headers(Map.of("x-goog-request-id", List.of("goog-req-99")));
        ModelErrorMapping m1 = ModelHttpErrorClassifier.classify(500, h, null, null);
        assertThat(m1.providerRequestId()).hasValue("goog-req-99");

        // From body request_id
        byte[] body = "{\"request_id\": \"body-req-88\"}".getBytes(StandardCharsets.UTF_8);
        ModelErrorMapping m2 = ModelHttpErrorClassifier.classify(500, emptyHeaders(), body, null);
        assertThat(m2.providerRequestId()).hasValue("body-req-88");
    }

    @Test
    void handleBodyExceedingSizeLimitGracefully() {
        byte[] hugeBody = new byte[100 * 1024];
        java.util.Arrays.fill(hugeBody, (byte) ' ');
        byte[] prefix = "{\"code\": \"fast_code\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, hugeBody, 0, prefix.length);

        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(400, emptyHeaders(), hugeBody, null);
        assertThat(mapping.providerCode()).isEqualTo("fast_code");
    }

    @Test
    void classifyWithInjectedReferenceInstantForHttpDateRetryAfter() {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        HttpHeaders headers = headers(Map.of("retry-after", List.of("Sun, 06 Sep 2026 12:01:00 GMT")));
        ModelErrorMapping mapping = ModelHttpErrorClassifier.classify(429, headers, null, null, now, null);
        assertThat(mapping.retryAfter()).hasValue(Duration.ofSeconds(60));
    }
}
