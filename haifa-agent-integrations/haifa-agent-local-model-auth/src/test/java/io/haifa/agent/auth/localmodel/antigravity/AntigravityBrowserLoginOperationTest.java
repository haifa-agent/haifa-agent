package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AntigravityBrowserLoginOperationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ATTEMPT_ID = "01890f6c-7b2a-7cc0-8000-000000000001";

    @Test
    void executeCompletesFlowWhenCallbackReturnsValidStateAndCode() throws Exception {
        int callbackPort = findAvailablePort();
        try (MockGoogleAuthServer googleServer = MockGoogleAuthServer.start()) {
            AntigravityOAuthClientRegistration registration = registration(googleServer.port(), callbackPort);
            AntigravityTokenClient tokenClient = new AntigravityTokenClient(
                    HttpClient.newHttpClient(),
                    JSON,
                    Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC),
                    Duration.ofSeconds(2),
                    registration);

            AtomicReference<URI> launchedUri = new AtomicReference<>();
            ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                    new ExternalLoginAttemptId(ATTEMPT_ID),
                    Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
                    uri -> true,
                    launchedUri::set,
                    snapshot -> {});

            AntigravityBrowserLoginOperation operation = new AntigravityBrowserLoginOperation(
                    registration, tokenClient, context, new AntigravityPkce(new SecureRandom()), Duration.ofSeconds(5));

            var executor = Executors.newSingleThreadExecutor();
            CompletableFuture<StoredExternalCredential> future =
                    CompletableFuture.supplyAsync(operation::execute, executor);

            // Wait for browser URL to be generated
            long start = System.currentTimeMillis();
            while (launchedUri.get() == null && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }

            URI authUri = launchedUri.get();
            assertThat(authUri).isNotNull();
            assertThat(authUri.getQuery()).contains("client_id=test-client-id");
            assertThat(authUri.getQuery()).contains("access_type=offline");
            assertThat(authUri.getQuery()).contains("prompt=consent");
            assertThat(authUri.getQuery()).contains("code_challenge_method=S256");

            // Extract state from authUri
            String state = extractQueryParam(authUri.getQuery(), "state");
            assertThat(state).isNotBlank();

            // A forged error callback must not terminate the active login attempt.
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest forgedErrorReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + callbackPort
                            + "/oauth-callback?error=access_denied&state=wrong-tampered-state"))
                    .GET()
                    .build();
            HttpResponse<String> forgedErrorResp = http.send(forgedErrorReq, HttpResponse.BodyHandlers.ofString());
            assertThat(forgedErrorResp.statusCode()).isEqualTo(400);
            assertThat(future).isNotCompleted();

            // Simulate browser redirect to callback server
            HttpRequest callbackReq = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + callbackPort + "/oauth-callback?code=test-auth-code&state=" + state))
                    .GET()
                    .build();
            HttpResponse<String> callbackResp = http.send(callbackReq, HttpResponse.BodyHandlers.ofString());
            assertThat(callbackResp.statusCode()).isEqualTo(200);
            assertThat(callbackResp.body())
                    .contains("Google Antigravity authorization received")
                    .doesNotContain("authentication successful");

            StoredExternalCredential credential = future.get(5, TimeUnit.SECONDS);
            assertThat(credential.reference()).isEqualTo(AntigravityBrowserLoginOperation.CREDENTIAL_REFERENCE);
            assertThat(credential.accessToken()).isEqualTo("google-access-token");
            assertThat(credential.refreshToken()).isEqualTo("google-refresh-token");
            assertThat(credential.accountId()).isEqualTo("antigravity-user@example.com");

            executor.shutdownNow();
        }
    }

    @Test
    void rejectsCallbackWithInvalidState() throws Exception {
        int callbackPort = findAvailablePort();
        try (MockGoogleAuthServer googleServer = MockGoogleAuthServer.start()) {
            AntigravityOAuthClientRegistration registration = registration(googleServer.port(), callbackPort);
            AntigravityTokenClient tokenClient = new AntigravityTokenClient(
                    HttpClient.newHttpClient(),
                    JSON,
                    Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC),
                    Duration.ofSeconds(2),
                    registration);

            AtomicReference<URI> launchedUri = new AtomicReference<>();
            ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                    new ExternalLoginAttemptId(ATTEMPT_ID),
                    Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
                    uri -> true,
                    launchedUri::set,
                    snapshot -> {});

            AntigravityBrowserLoginOperation operation = new AntigravityBrowserLoginOperation(
                    registration, tokenClient, context, new AntigravityPkce(new SecureRandom()), Duration.ofSeconds(2));

            var executor = Executors.newSingleThreadExecutor();
            CompletableFuture<StoredExternalCredential> future =
                    CompletableFuture.supplyAsync(operation::execute, executor);

            long start = System.currentTimeMillis();
            while (launchedUri.get() == null && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }

            HttpClient http = HttpClient.newHttpClient();
            HttpRequest callbackReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + callbackPort
                            + "/oauth-callback?code=test-auth-code&state=wrong-tampered-state"))
                    .GET()
                    .build();
            HttpResponse<String> callbackResp = http.send(callbackReq, HttpResponse.BodyHandlers.ofString());
            assertThat(callbackResp.statusCode()).isEqualTo(400);

            assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);

            executor.shutdownNow();
        }
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String extractQueryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return "";
    }

    private static AntigravityOAuthClientRegistration registration(int port, int callbackPort) {
        String base = "http://127.0.0.1:" + port;
        return new AntigravityOAuthClientRegistration(
                "test-antigravity-reg",
                "test-client-id",
                "test-client-secret",
                URI.create(base + "/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/userinfo"),
                URI.create(base + "/cloudcode"),
                URI.create(base + "/daily"),
                URI.create("http://127.0.0.1:" + callbackPort + "/oauth-callback"),
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                "Antigravity",
                true,
                true,
                true);
    }

    private record MockGoogleAuthServer(HttpServer server, int port) implements AutoCloseable {
        static MockGoogleAuthServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                String resp =
                        """
                        {
                            "access_token": "google-access-token",
                            "refresh_token": "google-refresh-token",
                            "expires_in": 3600
                        }
                        """;
                sendJson(exchange, 200, resp);
            });
            server.createContext("/userinfo", exchange -> {
                sendJson(exchange, 200, "{\"email\": \"antigravity-user@example.com\"}");
            });
            server.createContext("/cloudcode/v1internal:loadCodeAssist", exchange -> {
                String resp =
                        """
                        {
                            "cloudaicompanionProject": {
                                "id": "test-project-999"
                            },
                            "paidTier": {
                                "id": "g1-tier",
                                "availableCredits": [
                                    {
                                        "creditType": "GOOGLE_ONE_AI",
                                        "creditAmount": "200.0",
                                        "minimumCreditAmountForUsage": "1.0"
                                    }
                                ]
                            }
                        }
                        """;
                sendJson(exchange, 200, resp);
            });
            server.start();
            return new MockGoogleAuthServer(server, server.getAddress().getPort());
        }

        private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
