package io.haifa.agent.auth.localmodel.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

class CodexBrowserLoginOperationTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String ACCOUNT_ID = "account-login-1";
    private static final String ATTEMPT_ID = "01890f6c-7b2a-7cc0-8000-000000000001";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private HttpServer oauthServer;
    private int callbackPort;

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
    void browserPkceCallbackReturnsCredentialAndSafeProgress() {
        AtomicReference<URI> opened = new AtomicReference<>();
        AtomicReference<URI> presented = new AtomicReference<>();
        AtomicReference<String> progress = new AtomicReference<>();
        CodexBrowserLoginOperation operation = operation(
                uri -> {
                    opened.set(uri);
                    Map<String, String> query = query(uri.getRawQuery());
                    assertThat(query).containsEntry("code_challenge_method", "S256");
                    assertThat(query.get("code_challenge")).hasSize(43);
                    callback("authorization-code", query.get("state"));
                    return true;
                },
                Duration.ofSeconds(5),
                presented::set,
                snapshot -> progress.set(snapshot.toString()));

        StoredExternalCredential credential = operation.execute();

        assertThat(credential.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(credential.refreshToken()).isEqualTo("refresh-token");
        assertThat(tokenRequests).hasValue(1);
        assertThat(presented.get()).isEqualTo(opened.get());
        assertThat(opened.get().toString()).doesNotContain("authorization-code", "access-token", "refresh-token");
        assertThat(progress.get()).doesNotContain("authorization-code", "access-token", "refresh-token", "stub-client");
        assertThat(operation.snapshot().state()).isEqualTo(ExternalLoginAttemptState.EXCHANGING);
    }

    @Test
    void stateMismatchTimesOutAndClosesCallbackPort() throws Exception {
        CodexBrowserLoginOperation mismatch = operation(
                uri -> {
                    callback("authorization-code", "wrong-state");
                    return true;
                },
                Duration.ofMillis(100),
                snapshot -> {});
        assertThatThrownBy(mismatch::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTH_CALLBACK_TIMEOUT");
        assertThat(tokenRequests).hasValue(0);

        try (ServerSocket rebound = new ServerSocket()) {
            rebound.bind(new InetSocketAddress("127.0.0.1", callbackPort));
            assertThat(rebound.isBound()).isTrue();
        }
    }

    @Test
    void browserOpenFailurePublishesCopyableAuthorizationUrlAndStillAcceptsCallback() {
        AtomicReference<URI> fallback = new AtomicReference<>();
        AtomicReference<String> progressText = new AtomicReference<>();
        CodexBrowserLoginOperation operation = operation(
                uri -> false,
                Duration.ofSeconds(5),
                uri -> {
                    fallback.set(uri);
                    callback("authorization-code", query(uri.getRawQuery()).get("state"));
                },
                snapshot -> progressText.set(snapshot.toString()));

        StoredExternalCredential credential = operation.execute();

        assertThat(credential.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(fallback.get()).isNotNull();
        assertThat(query(fallback.get().getRawQuery()))
                .containsKeys("client_id", "code_challenge", "state", "redirect_uri");
        assertThat(progressText.get()).doesNotContain("stub-client", "client_id=", "code_challenge=", "&state=");
    }

    private CodexBrowserLoginOperation operation(
            ExternalLoginOperationContext.BrowserLauncher browser,
            Duration timeout,
            java.util.function.Consumer<io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot> progress) {
        return operation(browser, timeout, uri -> {}, progress);
    }

    private CodexBrowserLoginOperation operation(
            ExternalLoginOperationContext.BrowserLauncher browser,
            Duration timeout,
            java.util.function.Consumer<URI> browserAuthorization,
            java.util.function.Consumer<io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot> progress) {
        CodexOAuthClientRegistration registration = registration();
        CodexTokenClient tokens = new CodexTokenClient(
                HttpClient.newHttpClient(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                registration);
        ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                new ExternalLoginAttemptId(ATTEMPT_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                browser,
                browserAuthorization,
                progress);
        return new CodexBrowserLoginOperation(
                registration, tokens, context, new CodexPkce(new SecureRandom()), timeout);
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
            HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        String body = "{\"access_token\":\"" + jwt() + "\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600}";
        respond(exchange, 200, body);
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
