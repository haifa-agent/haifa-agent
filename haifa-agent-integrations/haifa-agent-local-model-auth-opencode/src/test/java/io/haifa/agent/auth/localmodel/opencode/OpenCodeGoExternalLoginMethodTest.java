package io.haifa.agent.auth.localmodel.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodUnavailableException;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.LocalModelAuthReference;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenCodeGoExternalLoginMethodTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalModelAuthReference REFERENCE =
            LocalModelAuthReference.parse("model-auth://opencode-go/default");

    @Test
    void exposesTheAuthorizationCodeModeOnlyAndIsSupportedByDefault() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> StubConsole.respond(exchange, 200, "{}"))) {
            OpenCodeGoExternalLoginMethod method = method(stub);

            assertThat(method.descriptor().methodId()).isEqualTo(OpenCodeGoExternalLoginMethod.METHOD_ID);
            assertThat(method.descriptor().supportedModes()).isEqualTo(Set.of(ExternalLoginMode.DEVICE_CODE));
            assertThat(method.descriptor().unofficial()).isFalse();
            assertThatThrownBy(() -> method.create(ExternalLoginMode.BROWSER, context(snapshot -> {})))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_LOGIN_MODE_UNAVAILABLE");
            assertThat(method.create(ExternalLoginMode.DEVICE_CODE, context(snapshot -> {})))
                    .isInstanceOf(OpenCodeGoDeviceLoginOperation.class);
        }
    }

    @Test
    void refreshesWithTheRegisteredClientAndKeepsTheBoundAccount() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/token")) {
                StubConsole.respond(
                        exchange,
                        200,
                        "{\"access_token\":\"access-token-2\",\"refresh_token\":\"refresh-token-2\",\"expires_in\":3600}");
            } else if (path.endsWith("/api/user")) {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-1\"}");
            } else {
                StubConsole.respond(exchange, 404, "{}");
            }
        })) {
            StoredExternalCredential refreshed =
                    method(stub).refresh(credential("account-1"), Instant.parse("2026-10-30T00:00:00Z"));

            assertThat(refreshed.accessToken()).isEqualTo("access-token-2");
            assertThat(refreshed.refreshToken()).isEqualTo("refresh-token-2");
            assertThat(refreshed.accountId()).isEqualTo("account-1");
            assertThat(refreshed.clientRegistrationRef()).isEqualTo("opencode-go-console-v1");
            assertThat(refreshed.methodId()).isEqualTo(OpenCodeGoExternalLoginMethod.METHOD_ID);
            assertThat(stub.requests())
                    .anyMatch(request -> request.startsWith("POST /console/auth/token "))
                    .anyMatch(request -> request.contains("grant_type=refresh_token"))
                    .anyMatch(request -> request.contains("client_id=opencode-cli"));
        }
    }

    @Test
    void mapsNonRetryableRefreshFailuresToReauthentication() throws Exception {
        try (StubConsole stub = StubConsole.start(
                (path, exchange) -> StubConsole.respond(exchange, 400, "{\"error\":\"invalid_grant\"}"))) {
            assertThatThrownBy(() -> method(stub).refresh(credential("account-1"), Instant.now()))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_REAUTH_REQUIRED");
        }
    }

    @Test
    void mapsRetryableRefreshFailuresToServiceUnavailable() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> StubConsole.respond(exchange, 503, "{}"))) {
            assertThatThrownBy(() -> method(stub).refresh(credential("account-1"), Instant.now()))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_LOGIN_SERVICE_UNAVAILABLE");
        }
    }

    @Test
    void treatsADifferentConsoleAccountAsReauthentication() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> {
            if (path.endsWith("/auth/token")) {
                StubConsole.respond(
                        exchange,
                        200,
                        "{\"access_token\":\"access-token-2\",\"refresh_token\":\"refresh-token-2\",\"expires_in\":3600}");
            } else {
                StubConsole.respond(exchange, 200, "{\"id\":\"account-2\"}");
            }
        })) {
            assertThatThrownBy(() -> method(stub).refresh(credential("account-1"), Instant.now()))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_REAUTH_REQUIRED");
        }
    }

    @Test
    void rejectsACredentialFromAnotherRegistration() throws Exception {
        try (StubConsole stub = StubConsole.start((path, exchange) -> StubConsole.respond(exchange, 200, "{}"))) {
            StoredExternalCredential foreign = new StoredExternalCredential(
                    REFERENCE,
                    OpenCodeGoExternalLoginMethod.METHOD_ID,
                    "someone-else",
                    "access",
                    "refresh",
                    2000,
                    1000,
                    "account-1");
            assertThatThrownBy(() -> method(stub).refresh(foreign, Instant.now()))
                    .isInstanceOf(ExternalLoginMethodUnavailableException.class)
                    .hasMessage("AUTH_REAUTH_REQUIRED");
            assertThat(stub.requests()).isEmpty();
        }
    }

    private static OpenCodeGoExternalLoginMethod method(StubConsole stub) {
        return new OpenCodeGoExternalLoginMethod(
                new OpenCodeGoTokenClient(
                        HttpClient.newHttpClient(), JSON, fixedClock(), Duration.ofSeconds(5), stub.endpoints()),
                duration -> {});
    }

    private static ExternalLoginOperationContext context(
            java.util.function.Consumer<io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot> progress) {
        return new ExternalLoginOperationContext(
                new ExternalLoginAttemptId("01890f6c-7b2a-7cc0-8000-000000000002"),
                fixedClock(),
                uri -> false,
                uri -> {},
                progress);
    }

    private static StoredExternalCredential credential(String accountId) {
        return new StoredExternalCredential(
                REFERENCE,
                OpenCodeGoExternalLoginMethod.METHOD_ID,
                OpenCodeGoEndpoints.CLIENT_REGISTRATION_REF,
                "access",
                "r",
                2_000,
                1_000,
                accountId,
                Optional.empty());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);
    }
}
