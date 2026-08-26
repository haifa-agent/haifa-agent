package io.haifa.agent.auth.localmodel.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AntigravityTokenClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exchangeSuccessWithUserInfoAndProject() throws Exception {
        try (MockGoogleServer server = MockGoogleServer.start()) {
            AntigravityTokenClient client = client(server.port());

            AntigravityTokenClient.TokenSet tokenSet = client.exchange(
                    "valid-auth-code", "valid-code-verifier", URI.create("http://127.0.0.1:51121/oauth-callback"));

            assertThat(tokenSet.accessToken()).isEqualTo("mock-access-token");
            assertThat(tokenSet.refreshToken()).isEqualTo("mock-refresh-token");
            assertThat(tokenSet.accountId()).isEqualTo("developer@example.com");
            assertThat(tokenSet.projectAndQuota().projectId()).isEqualTo("mock-project-12345");
            assertThat(tokenSet.projectAndQuota().creditAmount()).isEqualTo(150.0);
            assertThat(tokenSet.projectAndQuota().minCreditAmount()).isEqualTo(1.0);
            assertThat(tokenSet.projectAndQuota().creditsAvailable()).isTrue();
        }
    }

    @Test
    void singleFlightRefreshTriggersOnlyOneUpstreamRequest() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        try (MockGoogleServer server = MockGoogleServer.start(tokenCalls)) {
            AntigravityTokenClient client = client(server.port());

            int concurrency = 5;
            var executor = Executors.newFixedThreadPool(concurrency);
            var latch = new CountDownLatch(1);
            var futures = new CompletableFuture<?>[concurrency];

            for (int i = 0; i < concurrency; i++) {
                futures[i] = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                latch.await();
                                return client.refresh("shared-refresh-token");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        executor);
            }

            latch.countDown();
            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

            // Even with 5 concurrent callers, tokenEndpoint was only requested once
            assertThat(tokenCalls.get()).isEqualTo(1);
            executor.shutdownNow();
        }
    }

    @Test
    void onboardUserPollsUntilCompletion() throws Exception {
        AtomicInteger onboardAttempts = new AtomicInteger();
        try (MockGoogleServer server = MockGoogleServer.startWithOnboard(onboardAttempts)) {
            AntigravityTokenClient client = client(server.port(), duration -> {});

            AntigravityProjectAndQuota quota = client.fetchProjectAndQuota("mock-access-token");

            assertThat(quota.projectId()).isEqualTo("onboarded-project-67890");
            assertThat(onboardAttempts.get()).isEqualTo(3);
        }
    }

    @Test
    void rejectsUnboundedOrMalformedResponsesWithoutLeakingSecret() throws Exception {
        String canary = "confidential-secret-canary";
        try (MockGoogleServer server = MockGoogleServer.startFailing(429, "{\"error\":\"" + canary + "\"}")) {
            AntigravityTokenClient client = client(server.port());

            assertThatThrownBy(() -> client.refresh("secret-refresh-token"))
                    .isInstanceOfSatisfying(AntigravityTokenClient.AntigravityTokenException.class, ex -> {
                        assertThat(ex.retryable()).isTrue();
                        assertThat(ex.getMessage()).isEqualTo("AUTH_LOGIN_SERVICE_UNAVAILABLE");
                        assertThat(ex.toString()).doesNotContain(canary, "secret-refresh-token");
                    });
        }
    }

    private static AntigravityTokenClient client(int port) {
        return client(port, duration -> {});
    }

    private static AntigravityTokenClient client(int port, AntigravityTokenClient.Sleeper sleeper) {
        String base = "http://127.0.0.1:" + port;
        AntigravityOAuthClientRegistration registration = new AntigravityOAuthClientRegistration(
                "test-antigravity",
                "test-client-id",
                "test-client-secret",
                URI.create(base + "/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/userinfo"),
                URI.create(base + "/cloudcode"),
                URI.create(base + "/daily"),
                URI.create("http://127.0.0.1:51121/oauth-callback"),
                List.of("scope1"),
                "Antigravity",
                true,
                true);
        return new AntigravityTokenClient(
                HttpClient.newHttpClient(),
                JSON,
                Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC),
                Duration.ofSeconds(2),
                registration,
                sleeper);
    }

    private record MockGoogleServer(HttpServer server, int port) implements AutoCloseable {
        static MockGoogleServer start() throws IOException {
            return start(new AtomicInteger());
        }

        static MockGoogleServer start(AtomicInteger tokenCalls) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                tokenCalls.incrementAndGet();
                String resp =
                        """
                        {
                            "access_token": "mock-access-token",
                            "refresh_token": "mock-refresh-token",
                            "expires_in": 3600,
                            "token_type": "Bearer"
                        }
                        """;
                sendJson(exchange, 200, resp);
            });
            server.createContext("/userinfo", exchange -> {
                String resp = "{\"email\": \"developer@example.com\"}";
                sendJson(exchange, 200, resp);
            });
            server.createContext("/cloudcode/v1internal:loadCodeAssist", exchange -> {
                String resp =
                        """
                        {
                            "cloudaicompanionProject": {
                                "id": "mock-project-12345"
                            },
                            "paidTier": {
                                "id": "g1-tier",
                                "availableCredits": [
                                    {
                                        "creditType": "GOOGLE_ONE_AI",
                                        "creditAmount": "150.0",
                                        "minimumCreditAmountForUsage": "1.0"
                                    }
                                ]
                            }
                        }
                        """;
                sendJson(exchange, 200, resp);
            });
            server.start();
            return new MockGoogleServer(server, server.getAddress().getPort());
        }

        static MockGoogleServer startWithOnboard(AtomicInteger onboardAttempts) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/cloudcode/v1internal:loadCodeAssist", exchange -> {
                // Return no project to trigger onboard
                String resp =
                        """
                        {
                            "allowedTiers": [
                                { "id": "free-tier", "isDefault": true }
                            ]
                        }
                        """;
                sendJson(exchange, 200, resp);
            });
            server.createContext("/daily/v1internal:onboardUser", exchange -> {
                int attempt = onboardAttempts.incrementAndGet();
                if (attempt < 3) {
                    sendJson(exchange, 200, "{\"done\": false}");
                } else {
                    String resp =
                            """
                            {
                                "done": true,
                                "response": {
                                    "cloudaicompanionProject": "onboarded-project-67890"
                                }
                            }
                            """;
                    sendJson(exchange, 200, resp);
                }
            });
            server.start();
            return new MockGoogleServer(server, server.getAddress().getPort());
        }

        static MockGoogleServer startFailing(int status, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> sendJson(exchange, status, body));
            server.start();
            return new MockGoogleServer(server, server.getAddress().getPort());
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
