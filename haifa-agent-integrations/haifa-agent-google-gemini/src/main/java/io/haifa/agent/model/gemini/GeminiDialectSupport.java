package io.haifa.agent.model.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelErrorMapping;
import io.haifa.agent.model.api.ModelHttpErrorClassifier;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class GeminiDialectSupport {
    static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    static final Set<String> ANTIGRAVITY_DIRECT_HOSTS =
            Set.of("cloudcode-pa.googleapis.com", "daily-cloudcode-pa.googleapis.com");

    private GeminiDialectSupport() {}

    static boolean isLoopback(URI endpoint) {
        if (endpoint == null) return false;
        String host = endpoint.getHost();
        if (host == null || !LOOPBACK_NAMES.contains(host.toLowerCase(Locale.ROOT))) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException exception) {
            return false;
        }
    }

    static void validateEndpoint(URI endpoint, boolean allowInsecureLoopback) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!allowInsecureLoopback && !"https".equalsIgnoreCase(endpoint.getScheme()) && !isLoopback(endpoint)) {
            throw new IllegalArgumentException("insecure Gemini endpoint must be explicitly allowed loopback");
        }
    }

    static boolean isGovernedGoogleEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null) return false;
        return "https".equalsIgnoreCase(endpoint.getScheme())
                && "generativelanguage.googleapis.com".equalsIgnoreCase(endpoint.getHost());
    }

    static boolean isGovernedAntigravityDirectEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null) return false;
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String path = endpoint.getPath();
        while (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "https".equalsIgnoreCase(endpoint.getScheme())
                && ANTIGRAVITY_DIRECT_HOSTS.contains(host)
                && "/v1internal".equals(path);
    }

    static URI standardRequestUri(URI endpoint, String providerModelId, boolean stream) {
        String action = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        return endpointUri(endpoint, "/models/" + encodeModel(providerModelId) + action);
    }

    static URI antigravityDirectRequestUri(URI endpoint, boolean stream) {
        String action = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        return endpointUri(endpoint, action);
    }

    static URI endpointUri(URI base, String pathAndQuery) {
        String value = base.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + pathAndQuery);
    }

    static String encodeModel(String modelId) {
        return URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("model credential value must not be blank");
        }
        if (secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0 || secret.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("model credential contains invalid control characters");
        }
        return secret.trim();
    }

    static DialectErrorMapping classifyGeminiError(int status, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        ModelHttpErrorClassifier.DialectCustomizer customizer = (statusCode, hdrs, parsed, defaultMapping) -> {
            ModelErrorCategory category = defaultMapping.category();
            boolean retryable = defaultMapping.retryable();
            String code = defaultMapping.providerCode();

            if (parsed.status() != null && !parsed.status().isBlank()) {
                code = safeCode(parsed.status());
            }

            for (String reason : parsed.reasons()) {
                if ("QUOTA_EXHAUSTED".equalsIgnoreCase(reason)
                        || "INSUFFICIENT_G1_CREDITS_BALANCE".equalsIgnoreCase(reason)) {
                    code = safeCode(reason);
                    category = ModelErrorCategory.RATE_LIMITED;
                    retryable = false;
                    break;
                } else if ("RATE_LIMIT_EXCEEDED".equalsIgnoreCase(reason)) {
                    code = safeCode(reason);
                    category = ModelErrorCategory.RATE_LIMITED;
                    retryable = true;
                    break;
                }
            }

            if (statusCode == 429 && retryable && body != null && body.length > 0) {
                String bodyStr = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (bodyStr.contains("quota_exhausted")
                        || bodyStr.contains("quota exhausted")
                        || bodyStr.contains("insufficient_g1_credits_balance")) {
                    retryable = false;
                    category = ModelErrorCategory.RATE_LIMITED;
                    code = "quota_exhausted";
                }
            }
            return new ModelErrorMapping(
                    category,
                    retryable,
                    statusCode,
                    code,
                    defaultMapping.safeMessage(),
                    defaultMapping.retryAfter(),
                    defaultMapping.providerRequestId());
        };

        var mapping = ModelHttpErrorClassifier.classify(status, headers, body, null, customizer);
        return DialectErrorMapping.from(mapping);
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String safeCode(String value) {
        String code = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return code.isEmpty() ? "provider_error" : code.substring(0, Math.min(code.length(), 96));
    }
}
