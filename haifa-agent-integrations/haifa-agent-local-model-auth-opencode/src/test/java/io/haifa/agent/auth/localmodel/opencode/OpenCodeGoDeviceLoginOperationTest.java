package io.haifa.agent.auth.localmodel.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenCodeGoDeviceLoginOperationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ATTEMPT_ID = "01890f6c-7b2a-7cc0-8000-000000000001";

    @Test
    void handlesPendingAndSlowDownBeforeReturningCredential() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        List<ExternalLoginAttemptSnapshot> progress = new ArrayList<>();
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
                return;
            }
            if (path.endsWith("/auth/device/token")) {
                int attempt = polls.incrementAndGet();
                if (attempt == 1) {
                    StubConsole.respond(exchange, 429, "{\"error\":\"slow_down\"}");
                } else if (attempt == 2) {
                    StubConsole.respond(exchange, 400, "{\"error\":\"authorization_pending\"}");
                } else {
                    StubConsole.respond(exchange, 200, tokenResponse());
                }
                return;
            }
            if (path.endsWith("/api/user")) {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-1\"}");
                return;
            }
            StubConsole.respond(exchange, 404, "{}");
        })) {
            StoredExternalCredential credential =
                    operation(stub, sleeps::add, progress::add).execute();

            ExternalLoginAttemptSnapshot instruction = progress.stream()
                    .filter(value -> value.userCode().isPresent())
                    .findFirst()
                    .orElseThrow();
            assertThat(instruction.verificationUri()).contains(stub.verificationUri());
            assertThat(instruction.verificationUri().orElseThrow().getRawQuery())
                    .isNull();
            assertThat(instruction.userCode()).contains("TEST-CODE");
            assertThat(instruction.mode()).isEqualTo(ExternalLoginMode.DEVICE_CODE);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
            assertThat(credential.accountId()).isEqualTo("account-1");
            assertThat(credential.methodId()).isEqualTo(OpenCodeGoExternalLoginMethod.METHOD_ID);
            assertThat(credential.clientRegistrationRef()).isEqualTo("opencode-go-console-v1");
            assertThat(credential.reference().value()).isEqualTo("model-auth://opencode-go/default");
            assertThat(credential.accessToken()).isEqualTo("access-token");
            assertThat(credential.refreshToken()).isEqualTo("refresh-token");
            assertThat(credential.expiresAtEpochMillis() - credential.issuedAtEpochMillis())
                    .isEqualTo(Duration.ofSeconds(3600).toMillis());
        }
    }

    @Test
    void mapsDenialCancellationAndExpiryWithoutReturningCredential() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respond(exchange, 400, "{\"error\":\"access_denied\"}");
            } else {
                StubConsole.respond(exchange, 404, "{}");
            }
        })) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_DEVICE_CODE_DENIED");

            OpenCodeGoDeviceLoginOperation cancelled = operation(stub, duration -> {}, snapshot -> {});
            cancelled.cancel();
            assertThatThrownBy(cancelled::execute)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_CANCELLED");
        }

        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respond(exchange, 400, "{\"error\":\"expired_token\"}");
            } else {
                StubConsole.respond(exchange, 404, "{}");
            }
        })) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_DEVICE_CODE_EXPIRED");
        }
    }

    @Test
    void rejectsUnavailableMalformedAndOversizedResponses() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> StubConsole.respond(exchange, 404, "{}"))) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("AUTH_DEVICE_CODE_UNAVAILABLE");
        }

        try (StubConsole stub =
                StubConsole.start((path, exchange) -> StubConsole.respond(exchange, 200, "<html>not json</html>"))) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }

        String padding = "x".repeat(300 * 1024);
        try (StubConsole stub = StubConsole.start(
                (path, exchange) -> StubConsole.respond(exchange, 200, "{\"padding\":\"" + padding + "\"}"))) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("AUTH_DEVICE_RESPONSE_TOO_LARGE");
        }
    }

    @Test
    void rejectsSecondExecutionOfTheSameAttempt() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respond(exchange, 200, tokenResponse());
            } else {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-1\"}");
            }
        })) {
            OpenCodeGoDeviceLoginOperation operation = operation(stub, duration -> {}, snapshot -> {});
            operation.execute();
            assertThatThrownBy(operation::execute)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_ATTEMPT_ALREADY_USED");
        }
    }

    @Test
    void identifiesItselfInsteadOfTheJdkDefaultUserAgent() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respond(exchange, 400, "{\"error\":\"authorization_pending\"}");
            } else {
                StubConsole.respond(exchange, 404, "{}");
            }
        })) {
            OpenCodeGoDeviceLoginOperation operation = operation(stub, duration -> {}, snapshot -> {});
            operation.cancel();
            try {
                operation.execute();
            } catch (RuntimeException expected) {
                // Cancellation ends the attempt; the recorded requests are what this test checks.
            }

            assertThat(stub.userAgents()).isNotEmpty();
            assertThat(stub.userAgents()).allMatch(OpenCodeGoEndpoints.USER_AGENT::equals);
        }
    }

    @Test
    void resolvesTheConsoleRelativeVerificationPageWithoutItsQuery() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, relativeDeviceCodeResponse());
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respond(exchange, 200, tokenResponse());
            } else {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-1\"}");
            }
        })) {
            List<ExternalLoginAttemptSnapshot> progress = new ArrayList<>();
            operation(stub, duration -> {}, progress::add).execute();

            URI verification = progress.stream()
                    .filter(value -> value.userCode().isPresent())
                    .findFirst()
                    .orElseThrow()
                    .verificationUri()
                    .orElseThrow();
            assertThat(verification).isEqualTo(stub.verificationUri());
            assertThat(verification.getRawQuery()).isNull();
        }
    }

    @Test
    void mapsAnEdgeChallengePageToAnInvalidResponse() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, deviceCodeResponse(stubPortOf(exchange)));
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respondWith(exchange, 200, "text/html", "<html>blocked</html>");
            } else {
                StubConsole.respond(exchange, 404, "{}");
            }
        })) {
            assertThatThrownBy(() ->
                            operation(stub, duration -> {}, snapshot -> {}).execute())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        }
    }

    private OpenCodeGoDeviceLoginOperation operation(
            StubConsole stub,
            OpenCodeGoDeviceLoginOperation.Sleeper sleeper,
            java.util.function.Consumer<ExternalLoginAttemptSnapshot> progress) {
        ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                new ExternalLoginAttemptId(ATTEMPT_ID), fixedClock(), uri -> false, uri -> {}, progress);
        return new OpenCodeGoDeviceLoginOperation(client(stub), context, sleeper);
    }

    private OpenCodeGoTokenClient client(StubConsole stub) {
        return new OpenCodeGoTokenClient(
                HttpClient.newHttpClient(), JSON, fixedClock(), Duration.ofSeconds(5), stub.endpoints());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void acceptsTokenPayloadAliasesWhileKeepingTheLifetimeBounds() throws Exception {
        assertThat(credentialFor(tokenResponse()).refreshToken()).isEqualTo("refresh-token");

        long absoluteExpiry = fixedClock().millis() + Duration.ofSeconds(3600).toMillis();
        StoredExternalCredential camel = credentialFor("{\"accessToken\":\"access-token\","
                + "\"refreshToken\":\"refresh-token\",\"expiresAt\":" + absoluteExpiry + "}");
        assertThat(camel.accessToken()).isEqualTo("access-token");
        assertThat(camel.expiresAtEpochMillis() - camel.issuedAtEpochMillis())
                .isEqualTo(Duration.ofSeconds(3600).toMillis());

        StoredExternalCredential nested = credentialFor("{\"token\":" + tokenResponse() + "}");
        assertThat(nested.refreshToken()).isEqualTo("refresh-token");

        assertThatThrownBy(() -> credentialFor(
                        "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":5}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AUTH_DEVICE_CODE_RESPONSE_INVALID");
        assertThatThrownBy(() -> credentialFor("{\"access_token\":\"access-token\",\"expires_in\":3600}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AUTH_DEVICE_CODE_RESPONSE_INVALID");
    }

    private static int stubPortOf(com.sun.net.httpserver.HttpExchange exchange) {
        return exchange.getLocalAddress().getPort();
    }

    @Test
    void acceptsLongLivedTokensAndJsonWithoutTheJsonContentType() throws Exception {
        StoredExternalCredential monthly = credentialFor(
                "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":2592000}");
        assertThat(monthly.expiresAtEpochMillis() - monthly.issuedAtEpochMillis())
                .isEqualTo(Duration.ofDays(30).toMillis());

        StoredExternalCredential unlabelled = credentialFor(
                "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600}",
                "text/plain;charset=UTF-8");
        assertThat(unlabelled.accessToken()).isEqualTo("access-token");

        assertThatThrownBy(() -> credentialFor(
                        "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":30}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AUTH_DEVICE_CODE_RESPONSE_INVALID");
    }

    /** Completes one attempt against a stub that answers the first poll with the given token payload. */
    private StoredExternalCredential credentialFor(String tokenBody) throws Exception {
        return credentialFor(tokenBody, "application/json");
    }

    private StoredExternalCredential credentialFor(String tokenBody, String contentType) throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/device/code")) {
                StubConsole.respond(exchange, 200, relativeDeviceCodeResponse());
            } else if (path.endsWith("/auth/device/token")) {
                StubConsole.respondWith(exchange, 200, contentType, tokenBody);
            } else {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-1\"}");
            }
        })) {
            return operation(stub, duration -> {}, snapshot -> {}).execute();
        }
    }

    private static String deviceCodeResponse(int port) {
        return "{\"device_code\":\"device-code\",\"user_code\":\"TEST-CODE\","
                + "\"verification_uri_complete\":\"http://127.0.0.1:"
                + port
                + "/console/device?user_code=TEST-CODE\",\"expires_in\":900,\"interval\":5}";
    }

    private static String tokenResponse() {
        return "{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600}";
    }

    /** Shape captured from the live console: both verification fields are console-relative paths. */
    private static String relativeDeviceCodeResponse() {
        return "{\"device_code\":\"device-code\",\"user_code\":\"TEST-CODE\","
                + "\"verification_uri\":\"/console/device\","
                + "\"verification_uri_complete\":\"/console/device?user_code=TEST-CODE&client_id=opencode-cli\","
                + "\"expires_in\":600,\"interval\":5}";
    }
}
