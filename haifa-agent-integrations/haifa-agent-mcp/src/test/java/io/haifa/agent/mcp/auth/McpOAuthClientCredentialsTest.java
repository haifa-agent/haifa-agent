package io.haifa.agent.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class McpOAuthClientCredentialsTest {
    private HttpServer server;
    private URI tokenEndpoint;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String responseBody =
            "{\"access_token\":\"token-123\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();
        tokenEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesTokenAndCachesWithinValidityWindow() {
        var oauth = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "my-secret");
        String token1 = oauth.get();
        assertThat(token1).isEqualTo("token-123");
        assertThat(requestCount.get()).isEqualTo(1);

        String token2 = oauth.get();
        assertThat(token2).isEqualTo("token-123");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void refreshesTokenWhenExpiring() {
        AtomicLong currentTime = new AtomicLong(1_000_000L);
        Clock mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(currentTime.get());
            }
        };

        var oauth = new McpOAuthClientCredentials(
                tokenEndpoint,
                "my-client",
                "my-secret",
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                null,
                mutableClock);

        assertThat(oauth.get()).isEqualTo("token-123");
        assertThat(requestCount.get()).isEqualTo(1);

        // Advance time near expiration (expires_in = 3600s, so expires at 1_000_000 + 3_600_000)
        // refreshSkew = 30s = 30_000ms. If we advance by 3_580_000ms, it is within skew -> should refresh!
        responseBody = "{\"access_token\":\"token-456\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
        currentTime.addAndGet(3_580_000L);

        assertThat(oauth.get()).isEqualTo("token-456");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void handlesHttpErrorWithoutLeakingSecret() {
        responseStatus = 401;
        responseBody = "{\"error\":\"invalid_client\"}";

        var oauth = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "very-secret-password");
        assertThatThrownBy(oauth::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("very-secret-password");
    }

    @Test
    void rejectsResponseExceedingMaxSize() {
        responseBody =
                "{\"access_token\":\"" + "a".repeat(70 * 1024) + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

        var oauth = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "my-secret");
        assertThatThrownBy(oauth::get).isInstanceOf(IllegalStateException.class).hasMessageContaining("64KB");
    }

    @Test
    void supportsShortTtlWithAdaptiveSkew() {
        AtomicLong currentTime = new AtomicLong(1_000_000L);
        Clock mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(currentTime.get());
            }
        };

        // expires_in = 10s (10,000ms), configured skew = 30s.
        // Adaptive skew capped at lifetime / 2 = 5,000ms.
        responseBody = "{\"access_token\":\"short-1\",\"expires_in\":10,\"token_type\":\"Bearer\"}";
        var oauth = new McpOAuthClientCredentials(
                tokenEndpoint,
                "my-client",
                "my-secret",
                null,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                null,
                mutableClock);

        assertThat(oauth.get()).isEqualTo("short-1");
        assertThat(requestCount.get()).isEqualTo(1);

        // Advance 4 seconds (within 5s validity window) -> should hit cache!
        currentTime.addAndGet(4_000L);
        assertThat(oauth.get()).isEqualTo("short-1");
        assertThat(requestCount.get()).isEqualTo(1);

        // Advance another 2 seconds (total 6s elapsed, now in 5s skew before expiration) -> should refresh!
        responseBody = "{\"access_token\":\"short-2\",\"expires_in\":10,\"token_type\":\"Bearer\"}";
        currentTime.addAndGet(2_000L);
        assertThat(oauth.get()).isEqualTo("short-2");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void rejectsMissingOrUnsupportedTokenType() {
        responseBody = "{\"access_token\":\"token-123\",\"expires_in\":3600,\"token_type\":\"mac\"}";
        var oauthMac = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "my-secret");
        assertThatThrownBy(oauthMac::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token_type");

        responseBody = "{\"access_token\":\"token-123\",\"expires_in\":3600}";
        var oauthMissing = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "my-secret");
        assertThatThrownBy(oauthMissing::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token_type");
    }

    @Test
    void doesNotFollowRedirects() {
        responseStatus = 302;
        var oauth = new McpOAuthClientCredentials(tokenEndpoint, "my-client", "my-secret");
        assertThatThrownBy(oauth::get).isInstanceOf(IllegalStateException.class).hasMessageContaining("302");
    }
}
