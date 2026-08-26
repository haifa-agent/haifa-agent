package io.haifa.agent.auth.localmodel.antigravity;

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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded OAuth token exchange/refresh and project/quota initialization client for Google Antigravity. */
public final class AntigravityTokenClient {
    private static final System.Logger LOGGER = System.getLogger(AntigravityTokenClient.class.getName());
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_ONBOARD_ATTEMPTS = 5;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public record TokenSet(
            String accessToken,
            String refreshToken,
            long expiresAtEpochMillis,
            String accountId,
            long issuedAtEpochMillis,
            AntigravityProjectAndQuota projectAndQuota) {
        public TokenSet {
            Objects.requireNonNull(accessToken, "accessToken must not be null");
            Objects.requireNonNull(refreshToken, "refreshToken must not be null");
            Objects.requireNonNull(accountId, "accountId must not be null");
            Objects.requireNonNull(projectAndQuota, "projectAndQuota must not be null");
        }
    }

    public static final class AntigravityTokenException extends RuntimeException {
        private final boolean retryable;
        private final int status;

        public AntigravityTokenException(String message, boolean retryable, int status) {
            this(message, retryable, status, null);
        }

        public AntigravityTokenException(String message, boolean retryable, int status, Throwable cause) {
            super(message, cause);
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

    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;
    private final AntigravityOAuthClientRegistration registration;
    private final Sleeper sleeper;
    private final Map<String, CompletableFuture<TokenSet>> refreshFlights = new ConcurrentHashMap<>();

    public AntigravityTokenClient(
            HttpClient http,
            ObjectMapper json,
            Clock clock,
            Duration timeout,
            AntigravityOAuthClientRegistration registration) {
        this(http, json, clock, timeout, registration, duration -> Thread.sleep(duration.toMillis()));
    }

    public AntigravityTokenClient(
            HttpClient http,
            ObjectMapper json,
            Clock clock,
            Duration timeout,
            AntigravityOAuthClientRegistration registration,
            Sleeper sleeper) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Antigravity token timeout is invalid");
        }
    }

    public TokenSet exchange(String authorizationCode, String codeVerifier, URI redirectUri) {
        if (!registration.redirectUri().equals(redirectUri)) {
            throw new IllegalArgumentException("Antigravity OAuth redirect does not match client registration");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", registration.clientId());
        form.put("client_secret", registration.clientSecret());
        form.put("code", secret(authorizationCode, "authorizationCode"));
        form.put("code_verifier", secret(codeVerifier, "codeVerifier"));
        form.put("redirect_uri", redirectUri.toString());

        RawTokenResponse raw = requestToken(form, "exchange");
        String accountId = fetchUserInfo(raw.accessToken());
        AntigravityProjectAndQuota quota = fetchProjectAndQuota(raw.accessToken());
        long issuedAt = clock.instant().toEpochMilli();
        long expiresAt = Math.addExact(issuedAt, Math.multiplyExact(raw.expiresIn(), 1000));
        return new TokenSet(raw.accessToken(), raw.refreshToken(), expiresAt, accountId, issuedAt, quota);
    }

    public TokenSet refresh(String refreshToken) {
        String tokenKey = secret(refreshToken, "refreshToken");
        CompletableFuture<TokenSet> created = new CompletableFuture<>();
        CompletableFuture<TokenSet> existing = refreshFlights.putIfAbsent(tokenKey, created);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
                throw new AntigravityTokenException("AUTH_LOGIN_SERVICE_UNAVAILABLE", true, 0, exception);
            }
        }
        try {
            TokenSet result = doRefresh(tokenKey);
            created.complete(result);
            return result;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshFlights.remove(tokenKey, created);
        }
    }

    private TokenSet doRefresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", registration.clientId());
        form.put("client_secret", registration.clientSecret());

        RawTokenResponse raw = requestToken(form, "refresh");
        String effectiveRefreshToken = raw.refreshToken().isEmpty() ? refreshToken : raw.refreshToken();
        String accountId = fetchUserInfo(raw.accessToken());
        AntigravityProjectAndQuota quota = fetchProjectAndQuota(raw.accessToken());
        long issuedAt = clock.instant().toEpochMilli();
        long expiresAt = Math.addExact(issuedAt, Math.multiplyExact(raw.expiresIn(), 1000));
        return new TokenSet(raw.accessToken(), effectiveRefreshToken, expiresAt, accountId, issuedAt, quota);
    }

    public String fetchUserInfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(registration.userInfoEndpoint())
                .timeout(timeout)
                .header("Authorization", "Bearer " + secret(accessToken, "accessToken"))
                .header("User-Agent", registration.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        byte[] body = sendHttp(request, "userInfo");
        try {
            JsonNode root = json.readTree(body);
            String email = root.path("email").asText("").trim();
            if (email.isEmpty()) {
                throw new AntigravityTokenException("AUTH_USERINFO_INVALID", false, 0);
            }
            return email;
        } catch (IOException exception) {
            throw new AntigravityTokenException("AUTH_USERINFO_INVALID", false, 0, exception);
        }
    }

    public AntigravityProjectAndQuota fetchProjectAndQuota(String accessToken) {
        String loadUrl = registration.cloudCodeEndpoint().toString() + "/v1internal:loadCodeAssist";
        Map<String, Object> reqBody = Map.of("metadata", Map.of("ideType", "ANTIGRAVITY"));
        byte[] bodyBytes;
        try {
            bodyBytes = json.writeValueAsBytes(reqBody);
        } catch (IOException exception) {
            throw new AntigravityTokenException("AUTH_PROJECT_REQUEST_FAILED", false, 0, exception);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(loadUrl))
                .timeout(timeout)
                .header("Authorization", "Bearer " + secret(accessToken, "accessToken"))
                .header("User-Agent", registration.userAgent())
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        byte[] responseBytes = sendHttp(request, "loadCodeAssist");
        try {
            JsonNode root = json.readTree(responseBytes);
            String projectId = extractProject(root);
            String tierId = extractTierId(root);
            CreditsBalance credits = extractCredits(root);

            if (projectId.isEmpty()) {
                if (!registration.allowOnboarding()) {
                    throw new AntigravityTokenException("AUTH_ONBOARDING_CONFIRMATION_REQUIRED", false, 0);
                }
                projectId = onboardUser(accessToken, tierId);
            }
            if (projectId.isEmpty()) {
                throw new AntigravityTokenException("AUTH_PROJECT_NOT_FOUND", false, 0);
            }
            return AntigravityProjectAndQuota.of(projectId, tierId, credits.creditAmount(), credits.minCreditAmount());
        } catch (AntigravityTokenException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AntigravityTokenException("AUTH_PROJECT_RESPONSE_INVALID", false, 0, exception);
        }
    }

    public String onboardUser(String accessToken, String tierId) {
        String effectiveTier = tierId.isBlank() ? "free-tier" : tierId;
        String onboardUrl = registration.dailyCloudCodeEndpoint().toString() + "/v1internal:onboardUser";
        Map<String, Object> reqBody = Map.of(
                "tier_id",
                effectiveTier,
                "metadata",
                Map.of("ide_type", "ANTIGRAVITY", "ide_version", "1.0.0", "ide_name", "antigravity"));
        byte[] bodyBytes;
        try {
            bodyBytes = json.writeValueAsBytes(reqBody);
        } catch (IOException exception) {
            throw new AntigravityTokenException("AUTH_ONBOARD_REQUEST_FAILED", false, 0, exception);
        }

        for (int attempt = 1; attempt <= MAX_ONBOARD_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(onboardUrl))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + secret(accessToken, "accessToken"))
                    .header("User-Agent", registration.userAgent())
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            byte[] responseBytes = sendHttp(request, "onboardUser");
            try {
                JsonNode root = json.readTree(responseBytes);
                if (root.path("done").asBoolean(false)) {
                    JsonNode resp = root.path("response");
                    String projectId = extractProject(resp.isMissingNode() ? root : resp);
                    if (!projectId.isEmpty()) {
                        return projectId;
                    }
                }
            } catch (IOException ignored) {
            }

            if (attempt < MAX_ONBOARD_ATTEMPTS) {
                try {
                    sleeper.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AntigravityTokenException("AUTH_CANCELLED", false, 0, exception);
                }
            }
        }
        throw new AntigravityTokenException("AUTH_ONBOARD_TIMEOUT", false, 0);
    }

    private RawTokenResponse requestToken(Map<String, String> form, String operation) {
        HttpRequest request = HttpRequest.newBuilder(registration.tokenEndpoint())
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formUrlEncoded(form), StandardCharsets.UTF_8))
                .build();
        byte[] body = sendHttp(request, operation);
        try {
            JsonNode root = json.readTree(body);
            String accessToken = requiredText(root, "access_token");
            String refreshToken = root.path("refresh_token").asText("").trim();
            JsonNode expires = root.get("expires_in");
            if (expires == null || !expires.canConvertToLong()) {
                throw new AntigravityTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
            }
            long expiresIn = expires.longValue();
            if (expiresIn < 1 || expiresIn > Duration.ofDays(365).toSeconds()) {
                throw new AntigravityTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
            }
            LOGGER.log(System.Logger.Level.INFO, "Antigravity OAuth token {0} succeeded", operation);
            return new RawTokenResponse(accessToken, refreshToken, expiresIn);
        } catch (AntigravityTokenException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AntigravityTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0, exception);
        }
    }

    private byte[] sendHttp(HttpRequest request, String operation) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) {
                throw new AntigravityTokenException("AUTH_RESPONSE_TOO_LARGE", false, 0);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean retryable =
                        response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500;
                throw new AntigravityTokenException(
                        retryable ? "AUTH_LOGIN_SERVICE_UNAVAILABLE" : "AUTH_REAUTH_REQUIRED",
                        retryable,
                        response.statusCode());
            }
            return responseBody;
        } catch (AntigravityTokenException exception) {
            logFailure(operation, exception);
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            AntigravityTokenException failure = new AntigravityTokenException("AUTH_CANCELLED", false, 0, exception);
            logFailure(operation, failure);
            throw failure;
        } catch (IOException exception) {
            AntigravityTokenException failure =
                    new AntigravityTokenException("AUTH_LOGIN_SERVICE_UNAVAILABLE", true, 0, exception);
            logFailure(operation, failure);
            throw failure;
        }
    }

    private static String extractProject(JsonNode root) {
        if (root == null || !root.isObject()) return "";
        for (String field : List.of("cloudaicompanionProject", "projectId", "project")) {
            JsonNode node = root.get(field);
            if (node != null) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    return node.asText().trim();
                }
                if (node.isObject()
                        && node.has("id")
                        && !node.path("id").asText().isBlank()) {
                    return node.path("id").asText().trim();
                }
            }
        }
        return "";
    }

    private static String extractTierId(JsonNode root) {
        if (root == null || !root.isObject()) return "free-tier";
        JsonNode allowedTiers = root.get("allowedTiers");
        if (allowedTiers != null && allowedTiers.isArray()) {
            for (JsonNode tier : allowedTiers) {
                if (tier.path("isDefault").asBoolean(false) && tier.has("id")) {
                    String id = tier.path("id").asText("").trim();
                    if (!id.isEmpty()) return id;
                }
            }
        }
        JsonNode currentTier = root.get("currentTier");
        if (currentTier != null && currentTier.isObject() && currentTier.has("id")) {
            String id = currentTier.path("id").asText("").trim();
            if (!id.isEmpty()) return id;
        }
        return "free-tier";
    }

    private record CreditsBalance(double creditAmount, double minCreditAmount) {}

    private static CreditsBalance extractCredits(JsonNode root) {
        if (root == null || !root.isObject()) return new CreditsBalance(0.0, 0.0);
        JsonNode paidTier = root.get("paidTier");
        if (paidTier != null && paidTier.isObject()) {
            JsonNode creditsArray = paidTier.get("availableCredits");
            if (creditsArray != null && creditsArray.isArray()) {
                for (JsonNode credit : creditsArray) {
                    if ("GOOGLE_ONE_AI"
                            .equalsIgnoreCase(credit.path("creditType").asText(""))) {
                        double amount = credit.path("creditAmount").asDouble(0.0);
                        double minAmount =
                                credit.path("minimumCreditAmountForUsage").asDouble(0.0);
                        return new CreditsBalance(amount, minAmount);
                    }
                }
            }
        }
        return new CreditsBalance(0.0, 0.0);
    }

    private static void logFailure(String operation, AntigravityTokenException failure) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Antigravity OAuth token {0} failed: reason={1}, httpStatus={2}, retryable={3}",
                operation,
                failure.getMessage(),
                failure.status(),
                failure.retryable());
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new AntigravityTokenException("AUTH_TOKEN_RESPONSE_INVALID", false, 0);
        }
        return value.textValue().trim();
    }

    private static String secret(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String formUrlEncoded(Map<String, String> form) {
        StringBuilder builder = new StringBuilder();
        Iterator<Map.Entry<String, String>> iterator = form.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            if (iterator.hasNext()) builder.append('&');
        }
        return builder.toString();
    }

    private record RawTokenResponse(String accessToken, String refreshToken, long expiresIn) {}
}
