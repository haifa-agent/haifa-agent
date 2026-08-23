package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One bounded OpenAI Codex device-code login attempt. */
final class CodexDeviceLoginAttempt {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);

    private final CodexOAuthClientRegistration registration;
    private final CodexTokenClient tokens;
    private final CodingAuthFileStore store;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final Sleeper sleeper;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    CodexDeviceLoginAttempt(
            CodexOAuthClientRegistration registration,
            CodexTokenClient tokens,
            CodingAuthFileStore store,
            HttpClient http,
            ObjectMapper json,
            Clock clock,
            Sleeper sleeper) {
        this.registration = Objects.requireNonNull(registration, "registration must not be null");
        this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    void execute(Consumer<CodingDeviceLoginView> instructions) {
        Consumer<CodingDeviceLoginView> checkedInstructions =
                Objects.requireNonNull(instructions, "instructions must not be null");
        DeviceAuthorization device = start();
        long expiresAt = Math.addExact(clock.millis(), MAX_LIFETIME.toMillis());
        checkedInstructions.accept(
                new CodingDeviceLoginView(registration.deviceVerificationUri(), device.userCode(), expiresAt));
        Duration interval = device.interval();
        while (clock.millis() < expiresAt) {
            checkCancelled();
            PollResult poll = poll(device);
            if (poll.authorizationCode() != null) {
                CodexTokenClient.TokenSet tokenSet = tokens.exchange(
                        poll.authorizationCode(), poll.codeVerifier(), registration.deviceRedirectUri());
                store.save(CodingAuthCredential.oauth2(
                        CodexLoginAttempt.CREDENTIAL_REFERENCE,
                        tokenSet.accessToken(),
                        tokenSet.refreshToken(),
                        tokenSet.expiresAtEpochMillis(),
                        tokenSet.accountId(),
                        registration.reference(),
                        tokenSet.issuedAtEpochMillis()));
                return;
            }
            if (poll.slowDown()) {
                interval = interval.plusSeconds(5);
            }
            sleep(interval);
        }
        throw new IllegalStateException("AUTH_DEVICE_CODE_EXPIRED");
    }

    void cancel() {
        cancelled.set(true);
    }

    private DeviceAuthorization start() {
        var body = json.createObjectNode().put("client_id", registration.clientId());
        Response response = send(HttpRequest.newBuilder(registration.deviceUserCodeEndpoint())
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        if (response.status() == 404) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_UNAVAILABLE");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_REQUEST_FAILED");
        }
        JsonNode root = parseJson(response);
        String deviceAuthId = required(root, "device_auth_id", 512);
        String userCode = required(root, "user_code", 32);
        JsonNode intervalNode = root.get("interval");
        long intervalSeconds;
        try {
            intervalSeconds = intervalNode != null && intervalNode.isTextual()
                    ? Long.parseLong(intervalNode.textValue().trim())
                    : intervalNode == null ? 0 : intervalNode.longValue();
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID", exception);
        }
        if (intervalSeconds < 1 || intervalSeconds > 60) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }
        return new DeviceAuthorization(deviceAuthId, userCode, Duration.ofSeconds(intervalSeconds));
    }

    private PollResult poll(DeviceAuthorization device) {
        var body = json.createObjectNode()
                .put("device_auth_id", device.deviceAuthId())
                .put("user_code", device.userCode());
        Response response = send(HttpRequest.newBuilder(registration.deviceTokenEndpoint())
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        if (response.status() >= 200 && response.status() < 300) {
            JsonNode root = parseJson(response);
            return PollResult.complete(
                    required(root, "authorization_code", 64 * 1024), required(root, "code_verifier", 64 * 1024));
        }
        if (response.status() == 403 || response.status() == 404) {
            return PollResult.pending(false);
        }
        JsonNode root = parseJsonIfPossible(response);
        String code = errorCode(root);
        if ("deviceauth_authorization_pending".equals(code)) {
            return PollResult.pending(false);
        }
        if ("slow_down".equals(code) || response.status() == 429) {
            return PollResult.pending(true);
        }
        if ("access_denied".equals(code)) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_DENIED");
        }
        if ("expired_token".equals(code)) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_EXPIRED");
        }
        throw new IllegalStateException("AUTH_DEVICE_CODE_POLL_FAILED");
    }

    private Response send(HttpRequest request) {
        checkCancelled();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("AUTH_DEVICE_RESPONSE_TOO_LARGE");
                }
                return new Response(
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""),
                        bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AUTH_DEVICE_CODE_CANCELLED", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_UNAVAILABLE", exception);
        }
    }

    private JsonNode parseJson(Response response) {
        if (!response.contentType().toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID", exception);
        }
    }

    private JsonNode parseJsonIfPossible(Response response) {
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            return json.nullNode();
        }
    }

    private static String required(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new IllegalStateException("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }
        return text;
    }

    private static String errorCode(JsonNode root) {
        JsonNode error = root.path("error");
        if (error.isTextual()) return error.textValue();
        JsonNode code = error.path("code");
        return code.isTextual() ? code.textValue() : "";
    }

    private void sleep(Duration duration) {
        checkCancelled();
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AUTH_DEVICE_CODE_CANCELLED", exception);
        }
    }

    private void checkCancelled() {
        if (cancelled.get()) throw new IllegalStateException("AUTH_DEVICE_CODE_CANCELLED");
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper system() {
            return duration -> Thread.sleep(duration.toMillis());
        }
    }

    private record DeviceAuthorization(String deviceAuthId, String userCode, Duration interval) {}

    private record PollResult(String authorizationCode, String codeVerifier, boolean slowDown) {
        private static PollResult complete(String authorizationCode, String codeVerifier) {
            return new PollResult(authorizationCode, codeVerifier, false);
        }

        private static PollResult pending(boolean slowDown) {
            return new PollResult(null, null, slowDown);
        }
    }

    private record Response(int status, String contentType, byte[] body) {}
}
