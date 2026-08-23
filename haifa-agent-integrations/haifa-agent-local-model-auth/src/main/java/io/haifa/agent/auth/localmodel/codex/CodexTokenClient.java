package io.haifa.agent.auth.localmodel.codex;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded OAuth token exchange/refresh client. Raw responses never enter exceptions. */
public final class CodexTokenClient {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;
    private final CodexOAuthClientRegistration registration;

    public CodexTokenClient(
            HttpClient http,
            ObjectMapper json,
            Clock clock,
            Duration timeout,
            CodexOAuthClientRegistration registration) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Codex token timeout is invalid");
        }
    }

    TokenSet exchange(String authorizationCode, String codeVerifier, URI redirectUri) {
        if (!registration.redirectUri().equals(redirectUri)
                && !registration.deviceRedirectUri().equals(redirectUri)) {
            throw new IllegalArgumentException("Codex OAuth redirect does not match the client registration");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", registration.clientId());
        form.put("code", secret(authorizationCode, "authorizationCode"));
        form.put("code_verifier", secret(codeVerifier, "codeVerifier"));
        form.put("redirect_uri", redirectUri.toString());
        return request(form, "exchange");
    }

    TokenSet refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", secret(refreshToken, "refreshToken"));
        form.put("client_id", registration.clientId());
        return request(form, "refresh");
    }

    private TokenSet request(Map<String, String> form, String operation) {
        HttpRequest request = HttpRequest.newBuilder(registration.tokenEndpoint())
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) {
                throw new CodexTokenException("AUTH_TOKEN_RESPONSE_TOO_LARGE", false, 0);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean retryable =
                        response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500;
                throw new CodexTokenException(
                        retryable ? "AUTH_LOGIN_SERVICE_UNAVAILABLE" : "AUTH_REAUTH_REQUIRED",
                        retryable,
                        response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                throw new CodexTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
            }
            return parse(responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexTokenException("AUTH_CANCELLED", false, 0, exception);
        } catch (IOException exception) {
            throw new CodexTokenException("AUTH_LOGIN_SERVICE_UNAVAILABLE", true, 0, exception);
        }
    }

    private TokenSet parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            String accessToken = requiredText(root, "access_token");
            String refreshToken = requiredText(root, "refresh_token");
            JsonNode expires = root.get("expires_in");
            if (expires == null || !expires.canConvertToLong()) {
                throw new CodexTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
            }
            long expiresIn = expires.longValue();
            if (expiresIn < 1 || expiresIn > Duration.ofDays(365).toSeconds()) {
                throw new CodexTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
            }
            long issuedAt = clock.instant().toEpochMilli();
            long expiresAt = Math.addExact(issuedAt, Math.multiplyExact(expiresIn, 1000));
            return new TokenSet(accessToken, refreshToken, expiresAt, accountId(accessToken), issuedAt);
        } catch (CodexTokenException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CodexTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0, exception);
        }
    }

    private String accountId(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.", -1);
            if (parts.length != 3 || parts[1].isEmpty()) throw new IllegalArgumentException();
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(padded(parts[1])));
            JsonNode value = payload.path(AUTH_CLAIM).path("chatgpt_account_id");
            if (!value.isTextual()) throw new IllegalArgumentException();
            String accountId = value.textValue().trim();
            if (!accountId.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
            return accountId;
        } catch (IOException | IllegalArgumentException exception) {
            throw new CodexTokenException("AUTH_TOKEN_ACCOUNT_INVALID", false, 0, exception);
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new CodexTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
        }
        return value.textValue();
    }

    private static String padded(String value) {
        return switch (value.length() % 4) {
            case 0 -> value;
            case 2 -> value + "==";
            case 3 -> value + "=";
            default -> throw new IllegalArgumentException();
        };
    }

    private static String secret(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 64 * 1024) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    record TokenSet(
            String accessToken,
            String refreshToken,
            long expiresAtEpochMillis,
            String accountId,
            long issuedAtEpochMillis) {}

    public static final class CodexTokenException extends RuntimeException {
        private final boolean retryable;
        private final int status;

        CodexTokenException(String reasonCode, boolean retryable, int status) {
            this(reasonCode, retryable, status, null);
        }

        CodexTokenException(String reasonCode, boolean retryable, int status, Throwable cause) {
            super(reasonCode, cause);
            this.retryable = retryable;
            this.status = status;
        }

        public boolean retryable() {
            return retryable;
        }

        public int status() {
            return status;
        }
    }
}
