package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpAioClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUri;
    private final AtomicInteger execCalls = new AtomicInteger();
    private final AtomicReference<JsonNode> lastExec = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sandbox", exchange -> respond(exchange, 200, "{\"success\":true}"));
        server.createContext("/v1/bash/exec", exchange -> {
            execCalls.incrementAndGet();
            lastExec.set(mapper.readTree(exchange.getRequestBody()));
            respond(
                    exchange,
                    200,
                    "{\"success\":true,\"data\":{\"session_id\":\"s1\",\"command_id\":\"c1\","
                            + "\"status\":\"completed\",\"exit_code\":3,\"stdout\":\"out\",\"stderr\":\"err\"}}");
        });
        server.createContext("/v1/bash/kill", exchange -> respond(exchange, 200, "{\"success\":true}"));
        server.createContext("/v1/bash/sessions", exchange -> respond(exchange, 200, "{\"success\":true}"));
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsCompletedExitCodeAndSendsTheFixedRequestFields() {
        HttpAioClient client = new HttpAioClient(Duration.ofSeconds(2));
        AioClient.ExecResult result = client.exec(
                baseUri,
                new AioClient.ExecRequest(
                        "printf hello",
                        "/mnt/user-data/workspace",
                        Map.of("LANG", "C"),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        4096));
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(new String(result.stdout(), StandardCharsets.UTF_8)).isEqualTo("out");
        assertThat(new String(result.stderr(), StandardCharsets.UTF_8)).isEqualTo("err");
        assertThat(execCalls).hasValue(1);
        assertThat(lastExec.get().get("command").asText()).isEqualTo("printf hello");
        assertThat(lastExec.get().get("exec_dir").asText()).isEqualTo("/mnt/user-data/workspace");
        assertThat(lastExec.get().get("env").get("LANG").asText()).isEqualTo("C");
        assertThat(lastExec.get().get("hard_timeout").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(lastExec.get().get("max_output_length").asInt()).isEqualTo(4096);
    }

    @Test
    void runningStatusDoesNotBecomeAnExitCode() {
        server.removeContext("/v1/bash/exec");
        server.createContext(
                "/v1/bash/exec",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"success\":true,\"data\":{\"session_id\":\"s1\",\"command_id\":\"c1\","
                                + "\"status\":\"running\",\"stdout\":\"partial\"}}"));
        HttpAioClient client = new HttpAioClient(Duration.ofSeconds(2));
        AioClient.ExecResult result = client.exec(
                baseUri,
                new AioClient.ExecRequest(
                        "sleep 1", "/workspace", Map.of(), Duration.ofSeconds(1), Duration.ofSeconds(1), 1024));
        assertThat(result.status()).isEqualTo("running");
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void httpFailureIsNotRetried() {
        server.removeContext("/v1/bash/exec");
        server.createContext("/v1/bash/exec", exchange -> respond(exchange, 500, "{}"));
        HttpAioClient client = new HttpAioClient(Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.exec(
                        baseUri,
                        new AioClient.ExecRequest(
                                "true", "/workspace", Map.of(), Duration.ofSeconds(1), Duration.ofSeconds(1), 1024)))
                .isInstanceOf(AioClient.AioProtocolException.class)
                .hasMessageContaining("rejected");
        assertThat(execCalls).hasValue(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
