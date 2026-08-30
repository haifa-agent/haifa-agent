package io.haifa.agent.auth.localmodel.codex;

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
import org.junit.jupiter.api.Test;

class CodexTokenClientTest {
    @Test
    void rejectsUnboundedOrNonJsonResponsesWithoutEchoingProviderBody() throws Exception {
        String canary = "provider-secret-canary";
        try (Stub stub = Stub.start(429, "application/json", "{\"error\":\"" + canary + "\"}")) {
            CodexTokenClient client = client(stub.port());

            assertThatThrownBy(() -> client.refresh("refresh-secret"))
                    .isInstanceOfSatisfying(CodexTokenClient.CodexTokenException.class, exception -> {
                        assertThat(exception.retryable()).isTrue();
                        assertThat(exception.getMessage()).isEqualTo("AUTH_LOGIN_SERVICE_UNAVAILABLE");
                        assertThat(exception.toString()).doesNotContain(canary, "refresh-secret");
                    });
        }
        try (Stub stub = Stub.start(200, "text/plain", canary)) {
            assertThatThrownBy(() -> client(stub.port()).refresh("refresh-secret"))
                    .isInstanceOf(CodexTokenClient.CodexTokenException.class)
                    .hasMessage("AUTH_TOKEN_RESPONSE_INVALID");
        }
    }

    private static CodexTokenClient client(int port) {
        String root = "http://127.0.0.1:" + port;
        CodexOAuthClientRegistration registration = new CodexOAuthClientRegistration(
                "test-registration",
                "test-client",
                URI.create(root + "/oauth/authorize"),
                URI.create(root + "/oauth/token"),
                URI.create(root + "/auth/callback"),
                URI.create(root + "/backend-api/codex"),
                "haifa",
                "haifa-test/1",
                false,
                true);
        return new CodexTokenClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
                Duration.ofSeconds(2),
                registration);
    }

    private record Stub(HttpServer server, int port) implements AutoCloseable {
        private static Stub start(int status, String contentType, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth/token", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return new Stub(server, server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
