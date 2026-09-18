package io.haifa.agent.auth.localmodel.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptSnapshot;
import io.haifa.agent.auth.localmodel.ExternalLoginOperationContext;
import io.haifa.agent.auth.localmodel.StoredExternalCredential;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodexDeviceLoginOperationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ATTEMPT_ID = "01890f6c-7b2a-7cc0-8000-000000000001";

    @Test
    void handlesSlowDownAndPendingBeforeReturningCredential() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        List<ExternalLoginAttemptSnapshot> progress = new ArrayList<>();
        try (Stub stub = Stub.start(exchange -> {
            int attempt = polls.incrementAndGet();
            if (attempt == 1) respond(exchange, 429, "{\"error\":\"slow_down\"}");
            else if (attempt == 2) {
                respond(exchange, 403, "{\"error\":{\"code\":\"deviceauth_authorization_pending\"}}");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"authorization_code\":\"authorization-code\",\"code_verifier\":\"code-verifier\"}");
            }
        })) {
            CodexOAuthClientRegistration registration = registration(stub.port());
            CodexDeviceLoginOperation operation = operation(registration, sleeps::add, progress::add);

            StoredExternalCredential credential = operation.execute();

            ExternalLoginAttemptSnapshot instruction = progress.stream()
                    .filter(value -> value.userCode().isPresent())
                    .findFirst()
                    .orElseThrow();
            assertThat(instruction.verificationUri())
                    .contains(URI.create("http://127.0.0.1:" + stub.port() + "/codex/device"));
            assertThat(instruction.userCode()).contains("TEST-CODE");
            assertThat(sleeps).containsExactly(Duration.ofSeconds(6), Duration.ofSeconds(6));
            assertThat(credential.accountId()).isEqualTo("account-1");
            assertThat(credential.clientRegistrationRef()).isEqualTo("test-registration");
        }
    }

    @Test
    void mapsDenialAndCancellationWithoutReturningCredential() throws Exception {
        try (Stub stub = Stub.start(exchange -> respond(exchange, 400, "{\"error\":\"access_denied\"}"))) {
            CodexOAuthClientRegistration registration = registration(stub.port());
            CodexDeviceLoginOperation denied = operation(registration, duration -> {}, snapshot -> {});
            assertThatThrownBy(denied::execute)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_DEVICE_CODE_DENIED");

            CodexDeviceLoginOperation cancelled = operation(registration, duration -> {}, snapshot -> {});
            cancelled.cancel();
            assertThatThrownBy(cancelled::execute)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_CANCELLED");
        }
    }

    private CodexDeviceLoginOperation operation(
            CodexOAuthClientRegistration registration,
            CodexDeviceLoginOperation.Sleeper sleeper,
            java.util.function.Consumer<ExternalLoginAttemptSnapshot> progress) {
        ExternalLoginOperationContext context = new ExternalLoginOperationContext(
                new ExternalLoginAttemptId(ATTEMPT_ID), fixedClock(), uri -> true, uri -> {}, progress);
        return new CodexDeviceLoginOperation(
                registration,
                new CodexTokenClient(
                        HttpClient.newHttpClient(), JSON, fixedClock(), Duration.ofSeconds(5), registration),
                context,
                HttpClient.newHttpClient(),
                JSON,
                sleeper);
    }

    private static CodexOAuthClientRegistration registration(int port) {
        String root = "http://127.0.0.1:" + port;
        return new CodexOAuthClientRegistration(
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
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String jwt() {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"https://api.openai.com/auth\":{" + "\"chatgpt_account_id\":\"account-1\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface PollHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Stub(HttpServer server, int port) implements AutoCloseable {
        private static Stub start(PollHandler poll) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/accounts/deviceauth/usercode",
                    exchange -> respond(
                            exchange,
                            200,
                            "{\"device_auth_id\":\"device-auth-id\",\"user_code\":\"TEST-CODE\",\"interval\":\"1\"}"));
            server.createContext("/api/accounts/deviceauth/token", poll::handle);
            server.createContext(
                    "/oauth/token",
                    exchange -> respond(
                            exchange,
                            200,
                            "{\"access_token\":\"" + jwt()
                                    + "\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600}"));
            server.start();
            return new Stub(server, server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
