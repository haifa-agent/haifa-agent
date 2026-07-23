package io.haifa.agent.application.project.tool.web.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.tool.web.WebDispatchState;
import io.haifa.agent.application.project.tool.web.WebFailureCode;
import io.haifa.agent.application.project.tool.web.WebProviderException;
import io.haifa.agent.application.project.tool.web.WebProviderInvocationContext;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialRequirement;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

final class WebHttpSupport {
    private WebHttpSupport() {}

    static CredentialRequirement credential(String definitionId, String purpose, String scope) {
        return new CredentialRequirement(
                new CredentialDefinitionId(definitionId), purpose, Set.of(scope), CredentialExposureMode.HTTP_HEADER);
    }

    static <T> T withCredential(WebProviderInvocationContext context, Clock clock, Function<String, T> action) {
        if (context.credentialLeases().size() != 1) {
            throw failure(
                    WebFailureCode.WEB_CREDENTIAL_MISSING,
                    WebDispatchState.NOT_DISPATCHED,
                    "web provider credential is unavailable");
        }
        CredentialLease lease = context.credentialLeases().getFirst();
        if (lease.isClosed() || !lease.expiresAt().isAfter(clock.instant())) {
            throw failure(
                    WebFailureCode.WEB_CREDENTIAL_MISSING,
                    WebDispatchState.NOT_DISPATCHED,
                    "web provider credential is unavailable");
        }
        return lease.use(secret -> action.apply(new String(secret, StandardCharsets.UTF_8)));
    }

    static JsonNode postJson(
            HttpClient client,
            ObjectMapper mapper,
            URI endpoint,
            Map<String, Object> body,
            Map<String, String> headers,
            Duration configuredTimeout,
            int maxResponseBytes,
            WebProviderInvocationContext context,
            Clock clock) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw failure(
                    WebFailureCode.WEB_INVALID_REQUEST,
                    WebDispatchState.NOT_DISPATCHED,
                    "web provider request could not be encoded");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(effectiveTimeout(configuredTimeout, context, clock))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        headers.forEach(builder::header);
        return sendJson(client, mapper, builder.build(), maxResponseBytes, context);
    }

    static JsonNode getJson(
            HttpClient client,
            ObjectMapper mapper,
            URI endpoint,
            Map<String, String> headers,
            Duration configuredTimeout,
            int maxResponseBytes,
            WebProviderInvocationContext context,
            Clock clock) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(effectiveTimeout(configuredTimeout, context, clock))
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        return sendJson(client, mapper, builder.build(), maxResponseBytes, context);
    }

    static URI withQuery(URI endpoint, Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        new java.util.TreeMap<>(parameters).forEach((key, value) -> {
            if (!query.isEmpty()) query.append('&');
            query.append(encode(key)).append('=').append(encode(value));
        });
        return URI.create(endpoint.toString() + (endpoint.getRawQuery() == null ? "?" : "&") + query);
    }

    static Map<String, String> parameters() {
        return new LinkedHashMap<>();
    }

    static String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static URI absoluteUri(String value) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value, "url").trim()).normalize();
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.toString().length() > 4096
                    || !(uri.getScheme().equalsIgnoreCase("http")
                            || uri.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException exception) {
            throw failure(
                    WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                    WebDispatchState.ACKNOWLEDGED,
                    "web provider returned an invalid URL");
        }
    }

    static WebProviderException failure(WebFailureCode code, WebDispatchState state, String safeMessage) {
        return new WebProviderException(code, state, safeMessage);
    }

    static WebProviderException providerError(JsonNode root) {
        String code = root.path("errorCode").asText("");
        String message = root.path("errorMessage").asText("");
        if (code.isBlank() && message.isBlank()) return null;
        return failure(
                WebFailureCode.WEB_PROVIDER_FAILED,
                WebDispatchState.ACKNOWLEDGED,
                "web provider returned an application error" + (code.isBlank() ? "" : " (" + bounded(code, 64) + ")"));
    }

    private static JsonNode sendJson(
            HttpClient client,
            ObjectMapper mapper,
            HttpRequest request,
            int maxResponseBytes,
            WebProviderInvocationContext context) {
        if (context.cancellation().isCancellationRequested()) {
            throw failure(
                    WebFailureCode.WEB_CANCELLED,
                    WebDispatchState.NOT_DISPATCHED,
                    "web provider invocation was cancelled");
        }
        context.observer().dispatched();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new WebProviderException(
                    WebFailureCode.WEB_TIMEOUT,
                    WebDispatchState.OUTCOME_UNKNOWN,
                    "web provider invocation timed out",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            WebFailureCode code = context.cancellation().isCancellationRequested()
                    ? WebFailureCode.WEB_CANCELLED
                    : WebFailureCode.WEB_PROVIDER_FAILED;
            throw new WebProviderException(
                    code, WebDispatchState.OUTCOME_UNKNOWN, "web provider invocation was interrupted", exception);
        } catch (IOException exception) {
            throw new WebProviderException(
                    WebFailureCode.WEB_PROVIDER_FAILED,
                    WebDispatchState.OUTCOME_UNKNOWN,
                    "web provider transport failed",
                    exception);
        }
        context.observer().acknowledged();
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status < 200 || status >= 300) {
                body.readNBytes(Math.min(maxResponseBytes, 4096));
                throw httpFailure(status);
            }
            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw failure(
                        WebFailureCode.WEB_RESPONSE_TOO_LARGE,
                        WebDispatchState.ACKNOWLEDGED,
                        "web provider response exceeded the configured limit");
            }
            if (bytes.length == 0) {
                throw failure(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "web provider returned an empty response");
            }
            try {
                return mapper.readTree(bytes);
            } catch (JsonProcessingException exception) {
                throw new WebProviderException(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "web provider returned invalid JSON",
                        exception);
            }
        } catch (IOException exception) {
            throw new WebProviderException(
                    WebFailureCode.WEB_PROVIDER_FAILED,
                    WebDispatchState.ACKNOWLEDGED,
                    "web provider response could not be read",
                    exception);
        }
    }

    private static Duration effectiveTimeout(Duration configured, WebProviderInvocationContext context, Clock clock) {
        Duration remaining = Duration.between(clock.instant(), context.deadline());
        if (remaining.isZero() || remaining.isNegative()) {
            throw failure(
                    WebFailureCode.WEB_TIMEOUT, WebDispatchState.NOT_DISPATCHED, "web provider deadline has elapsed");
        }
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private static WebProviderException httpFailure(int status) {
        WebFailureCode code =
                switch (status) {
                    case 400, 422 -> WebFailureCode.WEB_INVALID_REQUEST;
                    case 401, 403 -> WebFailureCode.WEB_AUTH_FAILED;
                    case 402, 432, 433 -> WebFailureCode.WEB_QUOTA_EXHAUSTED;
                    case 408, 504 -> WebFailureCode.WEB_TIMEOUT;
                    case 413 -> WebFailureCode.WEB_RESPONSE_TOO_LARGE;
                    case 429 -> WebFailureCode.WEB_RATE_LIMITED;
                    default -> WebFailureCode.WEB_PROVIDER_FAILED;
                };
        return failure(code, WebDispatchState.ACKNOWLEDGED, "web provider returned HTTP status " + status);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
