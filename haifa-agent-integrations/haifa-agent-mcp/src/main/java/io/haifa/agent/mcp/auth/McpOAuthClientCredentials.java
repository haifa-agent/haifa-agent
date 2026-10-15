package io.haifa.agent.mcp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lightweight, thread-safe OAuth 2.0 client credentials token supplier.
 *
 * <p>Supports the standard {@code OAuth 2.0} {@code client_credentials} grant
 * with {@code client_secret_post} client authentication and {@code Bearer} access tokens.
 *
 * <p>Acquires access tokens via standard HTTP POST to the token endpoint and automatically
 * refreshes them prior to expiration with single-flight concurrency protection.
 * Token values and client secrets are kept out of exception diagnostics.
 */
public final class McpOAuthClientCredentials implements Supplier<String> {
    private static final Duration DEFAULT_REFRESH_SKEW = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final List<String> scopes;
    private final Duration refreshSkew;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Clock clock;
    private final ObjectMapper json;
    private final Object lock = new Object();

    private volatile String currentAccessToken;
    private volatile long expiresAtEpochMillis;
    private volatile long effectiveSkewMillis;

    public McpOAuthClientCredentials(URI tokenEndpoint, String clientId, String clientSecret) {
        this(
                tokenEndpoint,
                clientId,
                clientSecret,
                List.of(),
                DEFAULT_REFRESH_SKEW,
                DEFAULT_REQUEST_TIMEOUT,
                null,
                null);
    }

    public McpOAuthClientCredentials(
            URI tokenEndpoint,
            String clientId,
            String clientSecret,
            List<String> scopes,
            Duration refreshSkew,
            Duration requestTimeout,
            HttpClient httpClient,
            Clock clock) {
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint must not be null");
        this.clientId = requireNonBlank(clientId, "clientId");
        this.clientSecret = requireNonBlank(clientSecret, "clientSecret");
        this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        this.refreshSkew = refreshSkew == null ? DEFAULT_REFRESH_SKEW : requirePositive(refreshSkew, "refreshSkew");
        this.effectiveSkewMillis = this.refreshSkew.toMillis();
        this.requestTimeout =
                requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requirePositive(requestTimeout, "requestTimeout");
        this.httpClient = httpClient != null
                ? httpClient
                : HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.json = new ObjectMapper();
    }

    @Override
    public String get() {
        long now = clock.millis();
        String cached = currentAccessToken;
        if (cached != null && now < (expiresAtEpochMillis - effectiveSkewMillis)) {
            return cached;
        }
        synchronized (lock) {
            now = clock.millis();
            cached = currentAccessToken;
            if (cached != null && now < (expiresAtEpochMillis - effectiveSkewMillis)) {
                return cached;
            }
            return fetchToken(now);
        }
    }

    private String fetchToken(long now) {
        StringBuilder form = new StringBuilder();
        form.append("grant_type=").append(encode("client_credentials"));
        form.append("&client_id=").append(encode(clientId));
        form.append("&client_secret=").append(encode(clientSecret));
        if (!scopes.isEmpty()) {
            form.append("&scope=").append(encode(String.join(" ", scopes)));
        }

        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth token request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("OAuth token request failed due to I/O error", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OAuth token endpoint returned status code: " + response.statusCode());
        }

        byte[] bodyBytes;
        try (InputStream in = response.body()) {
            bodyBytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read OAuth token response body", exception);
        }

        if (bodyBytes.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("OAuth token response exceeded maximum allowed size of 64KB");
        }

        try {
            JsonNode root = json.readTree(bodyBytes);
            JsonNode typeNode = root.path("token_type");
            if (typeNode.isMissingNode()
                    || !"bearer".equalsIgnoreCase(typeNode.asText().trim())) {
                throw new IllegalStateException(
                        "OAuth response missing or unsupported token_type; only Bearer is supported");
            }
            JsonNode tokenNode = root.path("access_token");
            if (tokenNode.isMissingNode() || tokenNode.asText().isBlank()) {
                throw new IllegalStateException("OAuth response missing access_token");
            }
            String token = tokenNode.asText().trim();
            long expiresIn = root.hasNonNull("expires_in")
                    ? root.get("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS)
                    : DEFAULT_EXPIRES_IN_SECONDS;
            if (expiresIn <= 0) {
                expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
            }

            long lifetimeMillis = expiresIn * 1000L;
            long maxAllowedSkewMillis = Math.max(1L, lifetimeMillis / 2);
            long resolvedSkewMillis = Math.min(refreshSkew.toMillis(), maxAllowedSkewMillis);

            this.currentAccessToken = token;
            this.expiresAtEpochMillis = now + lifetimeMillis;
            this.effectiveSkewMillis = resolvedSkewMillis;
            return token;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse OAuth token response JSON", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
