package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexLoginAttemptTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String ACCOUNT_ID = "account-login-1";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicReference<String> tokenForm = new AtomicReference<>();
    private HttpServer oauthServer;
    private int callbackPort;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void startServer() throws IOException {
        oauthServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oauthServer.createContext("/oauth/token", this::handleToken);
        oauthServer.start();
        try (ServerSocket available = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            callbackPort = available.getLocalPort();
        }
    }

    @AfterEach
    void stopServer() {
        oauthServer.stop(0);
    }

    @Test
    void browserPkceCallbackExchangesAndStoresCredentialOnlyAfterSuccess() {
        CodingAuthFileStore store = store();
        AtomicReference<URI> opened = new AtomicReference<>();
        CodexLoginAttempt attempt = attempt(store, uri -> {
            opened.set(uri);
            Map<String, String> query = query(uri.getRawQuery());
            assertThat(query).containsEntry("code_challenge_method", "S256");
            assertThat(query.get("code_challenge")).hasSize(43);
            assertThat(query.get("state")).hasSizeGreaterThanOrEqualTo(32);
            callback("authorization-code", query.get("state"));
            return true;
        });

        CodexLoginAttempt.Result result = attempt.execute();

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.unofficialLocalCompatibility()).isTrue();
        assertThat(attempt.state()).isEqualTo(CodexLoginAttempt.State.SUCCEEDED);
        assertThat(opened.get().toString()).doesNotContain("authorization-code", "access-token", "refresh-token");
        assertThat(tokenRequests).hasValue(1);
        assertThat(tokenForm.get())
                .contains("grant_type=authorization_code")
                .contains("code=authorization-code")
                .contains("code_verifier=")
                .contains("redirect_uri=");
        assertThat(store.find(CodexLoginAttempt.CREDENTIAL_REFERENCE)).get().satisfies(saved -> {
            assertThat(saved.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(saved.refreshToken()).isEqualTo("refresh-token");
            assertThat(saved.clientRegistrationRef()).isEqualTo("stub-registration");
        });
    }

    @Test
    void stateMismatchFailsClosedWithoutTokenExchangeOrCredential() {
        CodingAuthFileStore store = store();
        CodexLoginAttempt attempt = attempt(
                store,
                uri -> {
                    callback("authorization-code", "wrong-state");
                    return true;
                },
                Duration.ofMillis(150));

        assertThatThrownBy(attempt::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTH_CALLBACK_TIMEOUT");
        assertThat(tokenRequests).hasValue(0);
        assertThat(store.find(CodexLoginAttempt.CREDENTIAL_REFERENCE)).isEmpty();
        assertThat(attempt.state()).isEqualTo(CodexLoginAttempt.State.FAILED);
    }

    @Test
    void browserFailureClosesCallbackPortAndDoesNotCreateAuthFile() throws Exception {
        CodingAuthFileStore store = store();
        CodexLoginAttempt attempt = attempt(store, ignored -> false);

        assertThatThrownBy(attempt::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTH_BROWSER_OPEN_FAILED");
        assertThat(store.find(CodexLoginAttempt.CREDENTIAL_REFERENCE)).isEmpty();
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.bind(new InetSocketAddress("127.0.0.1", callbackPort));
            assertThat(rebound.isBound()).isTrue();
        }
    }

    private CodexLoginAttempt attempt(CodingAuthFileStore store, CodexLoginAttempt.BrowserLauncher browser) {
        return attempt(store, browser, Duration.ofSeconds(5));
    }

    private CodexLoginAttempt attempt(
            CodingAuthFileStore store, CodexLoginAttempt.BrowserLauncher browser, Duration timeout) {
        CodexOAuthClientRegistration registration = registration();
        CodexTokenClient tokens = new CodexTokenClient(
                HttpClient.newHttpClient(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                registration);
        return new CodexLoginAttempt(registration, tokens, store, browser, new SecureRandom(), timeout);
    }

    private CodingAuthFileStore store() {
        return new CodingAuthFileStore(temporaryDirectory.resolve("auth.json"), json);
    }

    private CodexOAuthClientRegistration registration() {
        String oauthBase = "http://127.0.0.1:" + oauthServer.getAddress().getPort();
        return new CodexOAuthClientRegistration(
                "stub-registration",
                "stub-client",
                URI.create(oauthBase + "/oauth/authorize"),
                URI.create(oauthBase + "/oauth/token"),
                URI.create("http://127.0.0.1:" + callbackPort + "/auth/callback"),
                URI.create(oauthBase + "/backend-api/codex"),
                "haifa-stub",
                "haifa-agent-stub/1",
                true,
                true);
    }

    private void callback(String code, String state) {
        try {
            URI uri = URI.create("http://127.0.0.1:" + callbackPort + "/auth/callback?code="
                    + java.net.URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&state="
                    + java.net.URLEncoder.encode(state, StandardCharsets.UTF_8));
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isIn(200, 400);
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body =
                """
                {"access_token":"%s","refresh_token":"refresh-token","expires_in":3600}
                """
                        .formatted(jwt());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String jwt() {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                    + "."
                    + encoder.encodeToString(json.writeValueAsBytes(
                            Map.of("https://api.openai.com/auth", Map.of("chatgpt_account_id", ACCOUNT_ID))))
                    + ".signature";
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> query(String rawQuery) {
        return java.util.Arrays.stream(rawQuery.split("&"))
                .map(value -> value.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        value -> java.net.URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> java.net.URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
    }
}
