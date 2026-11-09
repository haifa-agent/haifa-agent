package io.haifa.agent.auth.localmodel.opencode;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded OpenCode Console device-grant, refresh, and account client.
 *
 * <p>Every response is size-capped and every failure maps onto one stable reason code; raw response bodies,
 * device codes, and tokens never enter an exception message or a log record.
 */
public final class OpenCodeGoTokenClient {
    /** Routes to the same JUL log the product already points users at; only shapes are ever logged. */
    private static final System.Logger LOG = System.getLogger(OpenCodeGoTokenClient.class.getName());

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_FIELD_CHARACTERS = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long MIN_TOKEN_LIFETIME_SECONDS = 60;
    /** Sanity ceiling only: the console decides its own token lifetime, and subscriptions may exceed a week. */
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 34_560_000;

    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration timeout;
    private final OpenCodeGoEndpoints endpoints;

    public OpenCodeGoTokenClient(
            HttpClient http, ObjectMapper json, Clock clock, Duration timeout, OpenCodeGoEndpoints endpoints) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("OpenCode Go token timeout is invalid");
        }
    }

    /** One bounded device authorization request result. The user code is public by design. */
    public record DeviceCode(String deviceCode, String userCode, URI verificationUri, Duration interval) {}

    /** One issued token pair with absolute epoch-millisecond boundaries. */
    public record TokenSet(
            String accessToken, String refreshToken, long expiresAtEpochMillis, long issuedAtEpochMillis) {}

    /** Poll outcome: pending, slow down, or a completed token pair. */
    public static final class PollOutcome {
        private final TokenSet tokens;
        private final boolean slowDown;

        private PollOutcome(TokenSet tokens, boolean slowDown) {
            this.tokens = tokens;
            this.slowDown = slowDown;
        }

        static PollOutcome pending() {
            return new PollOutcome(null, false);
        }

        static PollOutcome slowDownRequired() {
            return new PollOutcome(null, true);
        }

        static PollOutcome completed(TokenSet tokens) {
            return new PollOutcome(Objects.requireNonNull(tokens, "tokens must not be null"), false);
        }

        public boolean complete() {
            return tokens != null;
        }

        public boolean slowDown() {
            return slowDown;
        }

        public TokenSet tokens() {
            return tokens;
        }
    }

    /** Stable, retryable-aware failure. The message is always an {@code AUTH_*} reason code. */
    public static final class OpenCodeGoTokenException extends RuntimeException {
        private final boolean retryable;

        public OpenCodeGoTokenException(String reasonCode, boolean retryable) {
            super(reasonCode);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    public OpenCodeGoEndpoints endpoints() {
        return endpoints;
    }

    public DeviceCode requestDeviceCode() {
        var body = json.createObjectNode()
                .put("client_id", OpenCodeGoEndpoints.CLIENT_ID)
                .put("supports_org_scope", true);
        Response response = send(
                endpoints.deviceCodeUri(),
                request(endpoints.deviceCodeUri())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                "AUTH_DEVICE_CODE_UNAVAILABLE");
        if (response.status() == 404) throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_UNAVAILABLE", false);
        if (response.status() < 200 || response.status() >= 300) {
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_REQUEST_FAILED", response.status() >= 500);
        }
        JsonNode root = parseJson(response);
        return new DeviceCode(
                required(root, "device_code", 1024),
                required(root, "user_code", 32),
                verificationUri(root),
                interval(root));
    }

    public PollOutcome poll(String deviceCode) {
        var body = json.createObjectNode()
                .put("grant_type", OpenCodeGoEndpoints.DEVICE_GRANT_TYPE)
                .put("device_code", requireText(deviceCode, "deviceCode", MAX_FIELD_CHARACTERS))
                .put("client_id", OpenCodeGoEndpoints.CLIENT_ID);
        Response response = send(
                endpoints.deviceTokenUri(),
                request(endpoints.deviceTokenUri())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                "AUTH_DEVICE_CODE_UNAVAILABLE");
        if (response.status() >= 200 && response.status() < 300) {
            return PollOutcome.completed(tokenSet(parseJson(response)));
        }
        JsonNode root = parseJsonIfPossible(response);
        String code = errorCode(root);
        if ("authorization_pending".equals(code)) return PollOutcome.pending();
        if ("slow_down".equals(code) || response.status() == 429) return PollOutcome.slowDownRequired();
        if ("access_denied".equals(code)) throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_DENIED", false);
        if ("expired_token".equals(code)) throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_EXPIRED", false);
        throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_POLL_FAILED", false);
    }

    public TokenSet refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", requireText(refreshToken, "refreshToken", MAX_FIELD_CHARACTERS));
        form.put("client_id", OpenCodeGoEndpoints.CLIENT_ID);
        Response response = send(
                endpoints.tokenUri(),
                request(endpoints.tokenUri())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8)),
                "AUTH_LOGIN_SERVICE_UNAVAILABLE");
        if (response.status() >= 200 && response.status() < 300) {
            return tokenSet(parseJson(response));
        }
        JsonNode root = parseJsonIfPossible(response);
        String code = errorCode(root);
        if ("invalid_grant".equals(code)
                || "invalid_client".equals(code)
                || response.status() == 400
                || response.status() == 401) {
            throw new OpenCodeGoTokenException("AUTH_REAUTH_REQUIRED", false);
        }
        throw new OpenCodeGoTokenException("AUTH_LOGIN_SERVICE_UNAVAILABLE", true);
    }

    /** Resolves the stable console account identifier; never returns an email address as a fallback secret. */
    public String accountId(String accessToken) {
        String bearer = requireText(accessToken, "accessToken", MAX_FIELD_CHARACTERS);
        Response response = send(
                endpoints.userUri(),
                request(endpoints.userUri())
                        .header("Authorization", "Bearer " + bearer)
                        .GET(),
                "AUTH_LOGIN_SERVICE_UNAVAILABLE");
        if (response.status() == 401 || response.status() == 403) {
            throw new OpenCodeGoTokenException("AUTH_REAUTH_REQUIRED", false);
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new OpenCodeGoTokenException("AUTH_LOGIN_SERVICE_UNAVAILABLE", true);
        }
        JsonNode payload = unwrap(parseJson(response));
        String id = firstText(payload, "id", "user_id", "userId", "account_id", "accountId", "email");
        if (id == null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "opencode-go account payload lacks a stable identifier; present fields: {0}",
                    fieldNames(payload));
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        }
        return id;
    }

    /**
     * Maps one issued token payload.
     *
     * <p>The console documents snake_case fields; the camelCase aliases and the {@code token}/{@code data}
     * wrappers stay accepted because a subscription credential must never fail on a naming difference alone.
     * Every accepted value still goes through the same length, charset, and lifetime bounds.
     */
    private TokenSet tokenSet(JsonNode root) {
        JsonNode payload = unwrap(root);
        String accessToken = firstText(payload, "access_token", "accessToken", "access");
        String refreshToken = firstText(payload, "refresh_token", "refreshToken", "refresh");
        if (accessToken == null || refreshToken == null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "opencode-go token payload lacks a token field; present fields: {0}",
                    fieldNames(payload));
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        }
        long lifetimeSeconds = lifetimeSeconds(payload);
        long issuedAt = clock.millis();
        long expiresAt =
                Math.addExact(issuedAt, Duration.ofSeconds(lifetimeSeconds).toMillis());
        return new TokenSet(accessToken, refreshToken, expiresAt, issuedAt);
    }

    private static JsonNode unwrap(JsonNode root) {
        for (String wrapper : new String[] {"token", "data"}) {
            JsonNode nested = root.get(wrapper);
            if (nested != null && nested.isObject()) return nested;
        }
        return root;
    }

    private static String firstText(JsonNode payload, String... fields) {
        for (String field : fields) {
            String value = optionalText(payload.get(field));
            if (value != null) return requireText(value, field, MAX_FIELD_CHARACTERS);
        }
        return null;
    }

    /** Seconds until expiry, from a relative {@code expires_in} or an absolute {@code expires_at}. */
    private long lifetimeSeconds(JsonNode payload) {
        JsonNode relative = payload.get("expires_in");
        if (relative != null) return boundedLifetime(number(relative));
        JsonNode absolute = firstNode(payload, "expires_at", "expiresAt", "expires");
        if (absolute == null) throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        long expiresAt = absolute.isNumber() ? absolute.longValue() : absoluteEpochMillis(absolute.textValue());
        return boundedLifetime((expiresAt - clock.millis()) / 1000L);
    }

    private static JsonNode firstNode(JsonNode payload, String... fields) {
        for (String field : fields) {
            JsonNode node = payload.get(field);
            if (node != null && !node.isNull()) return node;
        }
        return null;
    }

    /** Names only, never values: enough to diagnose a contract change without leaking a secret. */
    private static List<String> fieldNames(JsonNode payload) {
        List<String> names = new ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

    private static long number(JsonNode node) {
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.textValue().trim());
            } catch (NumberFormatException exception) {
                throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
            }
        }
        return node.longValue();
    }

    private static long absoluteEpochMillis(String value) {
        String normalized = value == null ? "" : value.trim();
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            try {
                return java.time.Instant.parse(normalized).toEpochMilli();
            } catch (java.time.format.DateTimeParseException exception) {
                throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
            }
        }
    }

    private static long boundedLifetime(long seconds) {
        if (seconds < MIN_TOKEN_LIFETIME_SECONDS || seconds > MAX_TOKEN_LIFETIME_SECONDS) {
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        }
        return seconds;
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT.compareTo(timeout) >= 0 ? REQUEST_TIMEOUT : timeout)
                .header("User-Agent", OpenCodeGoEndpoints.USER_AGENT)
                .header("Accept", "application/json");
    }

    private Response send(URI uri, HttpRequest.Builder builder, String transportFailureCode) {
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new OpenCodeGoTokenException("AUTH_DEVICE_RESPONSE_TOO_LARGE", false);
                }
                return new Response(
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""),
                        bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenCodeGoTokenException("AUTH_CANCELLED", false);
        } catch (IOException exception) {
            throw new OpenCodeGoTokenException(transportFailureCode, true);
        } catch (IllegalArgumentException exception) {
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_UNAVAILABLE", true);
        }
    }

    private JsonNode parseJson(Response response) {
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "opencode-go console response is not JSON: status={0} contentType={1} bytes={2}",
                    response.status(),
                    response.contentType(),
                    response.body().length);
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        }
    }

    private JsonNode parseJsonIfPossible(Response response) {
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            return json.nullNode();
        }
    }

    private URI verificationUri(JsonNode root) {
        URI candidate = resolveDisplayUri(optionalText(root.get("verification_uri")));
        if (candidate == null) {
            candidate = resolveDisplayUri(optionalText(root.get("verification_uri_complete")));
        }
        return candidate == null ? endpoints.verificationUri() : candidate;
    }

    /**
     * Accepts the two documented forms: an absolute page URL, or a console-relative path such as
     * {@code /console/device}. The query is always dropped so a user code never travels in a link.
     */
    private URI resolveDisplayUri(String value) {
        if (value == null) return null;
        try {
            URI base = endpoints.consoleBase();
            URI parsed = URI.create(value);
            URI absolute = parsed.isAbsolute()
                    ? parsed
                    : URI.create(base.getScheme() + "://" + base.getRawAuthority() + "/")
                            .resolve(parsed);
            String scheme =
                    absolute.getScheme() == null ? "" : absolute.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            if (absolute.getHost() == null || absolute.getRawUserInfo() != null) return null;
            return URI.create(absolute.getScheme() + "://" + absolute.getRawAuthority()
                    + (absolute.getRawPath() == null ? "" : absolute.getRawPath()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Duration interval(JsonNode root) {
        long seconds = positiveLongOrDefault(root, "interval", 5L);
        return Duration.ofSeconds(Math.min(Math.max(seconds, 1L), 60L));
    }

    private static String required(JsonNode root, String field, int maxCharacters) {
        String value = optionalText(root.get(field));
        if (value == null) throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        return requireText(value, field, maxCharacters);
    }

    private static String optionalText(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.textValue().trim();
        return value.isEmpty() ? null : value;
    }

    private static String requireText(String value, String field, int maxCharacters) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maxCharacters || normalized.indexOf('\0') >= 0) {
            throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
        }
        return normalized;
    }

    private static long positiveLongOrDefault(JsonNode root, String field, long fallback) {
        JsonNode node = root.get(field);
        if (node == null) return fallback;
        long value;
        if (node.isTextual()) {
            try {
                value = Long.parseLong(node.textValue().trim());
            } catch (NumberFormatException exception) {
                throw new OpenCodeGoTokenException("AUTH_DEVICE_CODE_RESPONSE_INVALID", false);
            }
        } else {
            value = node.longValue();
        }
        return value < 1 ? fallback : value;
    }

    private static String errorCode(JsonNode root) {
        JsonNode error = root.path("error");
        if (error.isTextual()) return error.textValue();
        JsonNode code = error.path("code");
        return code.isTextual() ? code.textValue() : "";
    }

    private static String form(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach((key, value) -> {
            if (!builder.isEmpty()) builder.append('&');
            builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return builder.toString();
    }

    private record Response(int status, String contentType, byte[] body) {}
}
