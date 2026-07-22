package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpTelemetry;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamableHttpMcpComponentTest {
    @Test
    void runsInitializePaginatedListCallAndCloseAgainstOfflineJsonStub() throws Exception {
        try (StubServer stub = new StubServer("2025-11-25")) {
            var definition = McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now", "calculate"));
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            var snapshot = client.initialize(List.of());
            var first = client.listTools(null, List.of());
            var second = client.listTools(first.nextCursor().orElseThrow(), List.of());
            AtomicInteger dispatched = new AtomicInteger();
            var result = client.callTool("time_now", Map.of("zone", "UTC"), List.of(), observer(dispatched));
            client.close();
            client.close();

            assertThat(snapshot.negotiatedProtocolVersion()).isEqualTo("2025-11-25");
            assertThat(snapshot.toolsListChanged()).isTrue();
            assertThat(first.tools()).extracting(tool -> tool.name()).containsExactly("time_now");
            assertThat(second.tools()).extracting(tool -> tool.name()).containsExactly("calculate");
            assertThat(result.error()).isFalse();
            assertThat(result.structuredContent()).containsEntry("zone", "UTC");
            assertThat(dispatched).hasValue(1);
            assertThat(client.state()).isEqualTo(McpConnectionState.DISCONNECTED);
            assertThat(stub.methods()).contains("initialize", "notifications/initialized", "tools/list", "tools/call");
        }
    }

    private static io.haifa.agent.tool.api.ToolInvocationObserver observer(AtomicInteger dispatched) {
        return new io.haifa.agent.tool.api.ToolInvocationObserver() {
            @Override
            public void dispatched() {
                dispatched.incrementAndGet();
            }

            @Override
            public void acknowledged() {}
        };
    }

    private static McpTelemetry telemetry(List<String> events) {
        return new McpTelemetry() {
            @Override
            public void stateChanged(io.haifa.agent.mcp.config.McpServerId serverId, McpConnectionState state) {
                events.add("state:" + state);
            }

            @Override
            public void operationFailed(io.haifa.agent.mcp.config.McpServerId serverId, String errorCode) {
                events.add("error:" + errorCode);
            }
        };
    }

    @Test
    void rejectsNegotiationToAnyOtherProtocolVersionDeterministically() throws Exception {
        try (StubServer stub = new StubServer("2025-03-26")) {
            var definition = McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now"));
            List<String> telemetry = new CopyOnWriteArrayList<>();
            var client = new SdkMcpClientFactory(serverId -> {}, telemetry(telemetry))
                    .create(definition, McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_PROTOCOL_VERSION_MISMATCH"));
            assertThat(client.state()).isEqualTo(McpConnectionState.DISCONNECTED);
            assertThat(telemetry)
                    .contains("state:CONNECTING", "state:INITIALIZING", "error:MCP_PROTOCOL_VERSION_MISMATCH");
        }
    }

    @Test
    void acceptsEventStreamResponsesForPostOperations() throws Exception {
        try (StubServer stub = new StubServer("2025-11-25", true)) {
            var definition = McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now"));
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            client.initialize(List.of());
            var tools = client.listTools(null, List.of());
            var result = client.callTool(
                    "time_now",
                    Map.of("zone", "UTC"),
                    List.of(),
                    io.haifa.agent.tool.api.ToolInvocationObserver.noop());

            assertThat(tools.tools()).extracting(tool -> tool.name()).containsExactly("time_now");
            assertThat(result.structuredContent()).containsEntry("zone", "UTC");
            client.close();
        }
    }

    private static final class StubServer implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final String negotiatedVersion;
        private final boolean eventStream;
        private final List<String> methods = new CopyOnWriteArrayList<>();

        private StubServer(String negotiatedVersion) throws IOException {
            this(negotiatedVersion, false);
        }

        private StubServer(String negotiatedVersion, boolean eventStream) throws IOException {
            this.negotiatedVersion = negotiatedVersion;
            this.eventStream = eventStream;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        private java.net.URI endpoint() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private List<String> methods() {
            return List.copyOf(methods);
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod()) || "DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            methods.add(method);
            if (!request.containsKey("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result =
                    switch (method) {
                        case "initialize" ->
                            Map.of(
                                    "protocolVersion", negotiatedVersion,
                                    "capabilities", Map.of("tools", Map.of("listChanged", true)),
                                    "serverInfo", Map.of("name", "offline-stub", "version", "1.0.0"));
                        case "tools/list" -> listResult(request);
                        case "tools/call" ->
                            Map.of(
                                    "content", List.of(Map.of("type", "text", "text", "ok")),
                                    "structuredContent", Map.of("zone", "UTC"),
                                    "isError", false);
                        default -> throw new IllegalStateException("unexpected method " + method);
                    };
            String json =
                    mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            byte[] response = eventStream
                    ? ("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)
                    : json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", eventStream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private Map<String, Object> listResult(Map<String, Object> request) {
            Map<String, Object> params = request.get("params") instanceof Map<?, ?> value
                    ? mapper.convertValue(value, new TypeReference<>() {})
                    : Map.of();
            boolean second = "next".equals(params.get("cursor"));
            Map<String, Object> tool = Map.of(
                    "name",
                    second ? "calculate" : "time_now",
                    "description",
                    "offline tool",
                    "inputSchema",
                    Map.of("type", "object", "properties", Map.of()),
                    "outputSchema",
                    Map.of("type", "object", "additionalProperties", true));
            return second ? Map.of("tools", List.of(tool)) : Map.of("tools", List.of(tool), "nextCursor", "next");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
