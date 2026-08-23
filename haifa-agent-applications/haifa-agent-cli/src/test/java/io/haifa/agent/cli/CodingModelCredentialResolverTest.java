package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.model.api.CredentialRef;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingModelCredentialResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String ACCOUNT_ID = "account-1";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private HttpServer server;
    private volatile TokenResponse tokenResponse;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", this::handleToken);
        server.start();
        tokenResponse = TokenResponse.success(jwt(ACCOUNT_ID), "refresh-2", 3600);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void oneHundredConcurrentResolvesShareOneRefreshAndPersistRotation() throws Exception {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.oauth2(
                "openai-codex/default",
                jwt(ACCOUNT_ID),
                "refresh-1",
                NOW.minusSeconds(1).toEpochMilli(),
                ACCOUNT_ID,
                "stub-registration",
                NOW.minusSeconds(3600).toEpochMilli()));
        CodingModelCredentialResolver resolver = resolver(store, registration(), Duration.ofMinutes(5));
        List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 100)
                .mapToObj(ignored -> (Callable<String>)
                        () -> resolver.resolve(new CredentialRef("coding-auth://openai-codex/default"))
                                .value())
                .toList();

        try (var executor = Executors.newFixedThreadPool(20)) {
            var results = executor.invokeAll(tasks);
            for (var result : results) assertThat(result.get()).isEqualTo(jwt(ACCOUNT_ID));
        }

        assertThat(tokenRequests).hasValue(1);
        assertThat(store.find("openai-codex/default")).get().satisfies(saved -> {
            assertThat(saved.refreshToken()).isEqualTo("refresh-2");
            assertThat(saved.expiresAtEpochMillis())
                    .isEqualTo(NOW.plusSeconds(3600).toEpochMilli());
        });
    }

    @Test
    void transientRefreshFailureUsesStillValidTokenInsideTheSafetyWindow() {
        CodingAuthFileStore store = store();
        String oldAccessToken = jwt(ACCOUNT_ID);
        store.save(CodingAuthCredential.oauth2(
                "openai-codex/default",
                oldAccessToken,
                "refresh-1",
                NOW.plusSeconds(60).toEpochMilli(),
                ACCOUNT_ID,
                "stub-registration",
                NOW.minusSeconds(3600).toEpochMilli()));
        tokenResponse = new TokenResponse(503, "application/json", "{\"error\":\"unavailable\"}");

        String resolved = resolver(store, registration(), Duration.ofMinutes(5))
                .resolve(new CredentialRef("coding-auth://openai-codex/default"))
                .value();

        assertThat(resolved).isEqualTo(oldAccessToken);
        assertThat(tokenRequests).hasValue(1);
    }

    @Test
    void changedClientRegistrationRequiresReauthenticationWithoutSendingRefreshToken() {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.oauth2(
                "openai-codex/default",
                jwt(ACCOUNT_ID),
                "refresh-canary",
                NOW.minusSeconds(1).toEpochMilli(),
                ACCOUNT_ID,
                "different-registration",
                NOW.minusSeconds(3600).toEpochMilli()));

        assertThatThrownBy(() -> resolver(store, registration(), Duration.ofMinutes(5))
                        .resolve(new CredentialRef("coding-auth://openai-codex/default")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reauthentication")
                .hasMessageNotContaining("refresh-canary");
        assertThat(tokenRequests).hasValue(0);
    }

    @Test
    void localCompatibilityRequiresBothExplicitGateAndExternallyInjectedClientIdentity() {
        assertThat(CodexOAuthClientRegistration.localCompatibility(Map.of())).isEmpty();
        assertThatThrownBy(() -> CodexOAuthClientRegistration.localCompatibility(
                        Map.of("HAIFA_CODEX_LOCAL_COMPAT_TEST", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAIFA_CODEX_OAUTH_CLIENT_ID");

        var registration = CodexOAuthClientRegistration.localCompatibility(Map.of(
                        "HAIFA_CODEX_LOCAL_COMPAT_TEST",
                        "true",
                        "HAIFA_CODEX_OAUTH_CLIENT_ID",
                        "externally-injected-client",
                        "HAIFA_CODEX_ORIGINATOR",
                        "haifa-local"))
                .orElseThrow();

        assertThat(registration.clientId()).isEqualTo("externally-injected-client");
        assertThat(registration.unofficialLocalCompatibility()).isTrue();
    }

    private CodingModelCredentialResolver resolver(
            CodingAuthFileStore store, CodexOAuthClientRegistration registration, Duration safetyWindow) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CodexTokenClient tokens =
                new CodexTokenClient(HttpClient.newHttpClient(), json, clock, Duration.ofSeconds(5), registration);
        return new CodingModelCredentialResolver(
                ignored -> null, store, Optional.of(registration), Optional.of(tokens), clock, safetyWindow);
    }

    private CodingAuthFileStore store() {
        return new CodingAuthFileStore(temporaryDirectory.resolve("auth.json"), json);
    }

    private CodexOAuthClientRegistration registration() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CodexOAuthClientRegistration(
                "stub-registration",
                "stub-client",
                URI.create(base + "/oauth/authorize"),
                URI.create(base + "/oauth/token"),
                URI.create("http://127.0.0.1:1455/auth/callback"),
                URI.create(base + "/backend-api/codex"),
                "haifa-stub",
                "haifa-agent-stub/1",
                false,
                true);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!form.contains("grant_type=refresh_token") || !form.contains("refresh_token=refresh-1")) {
            tokenResponse = new TokenResponse(400, "application/json", "{\"error\":\"bad_request\"}");
        }
        TokenResponse configured = tokenResponse;
        exchange.getResponseHeaders().set("Content-Type", configured.contentType());
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(configured.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String jwt(String accountId) {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                    + "."
                    + encoder.encodeToString(json.writeValueAsBytes(
                            Map.of("https://api.openai.com/auth", Map.of("chatgpt_account_id", accountId))))
                    + ".signature";
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TokenResponse(int status, String contentType, String body) {
        private static TokenResponse success(String accessToken, String refreshToken, int expiresIn) {
            return new TokenResponse(
                    200,
                    "application/json",
                    """
                    {"access_token":"%s","refresh_token":"%s","expires_in":%d}
                    """
                            .formatted(accessToken, refreshToken, expiresIn));
        }
    }
}
