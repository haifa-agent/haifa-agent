package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.mcp.config.McpProtocolProfile;
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

class ModernHttpMcpComponentTest {
    @Test
    void usesStatelessDiscoveryAndPerRequestMetadataFor2026Protocol() throws Exception {
        try (ModernStubServer stub = new ModernStubServer()) {
            var definition =
                    McpTestFixtures.httpServer(stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28);
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            var snapshot = client.initialize(List.of());
            var tools = client.listTools(null, List.of());
            AtomicInteger dispatched = new AtomicInteger();
            var result =
                    client.callTool("echo", Map.of("region", "北京", "value", "hello"), List.of(), observer(dispatched));
            client.close();

            assertThat(snapshot.negotiatedProtocolVersion()).isEqualTo("2026-07-28");
            assertThat(tools.tools()).extracting(tool -> tool.name()).containsExactly("echo");
            assertThat(result.structuredContent()).containsEntry("value", "hello");
            assertThat(dispatched).hasValue(1);
            assertThat(stub.methods()).containsExactly("server/discover", "tools/list", "tools/call");
            assertThat(stub.requests()).allSatisfy(request -> {
                assertThat(request.httpMethod()).isEqualTo("POST");
                assertThat(request.sessionHeader()).isNull();
                assertThat(request.protocolHeader()).isEqualTo("2026-07-28");
                assertThat(request.methodHeader()).isEqualTo(request.method());
                assertThat(request.meta())
                        .containsEntry("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                        .containsKeys(
                                "io.modelcontextprotocol/clientInfo", "io.modelcontextprotocol/clientCapabilities");
            });
            assertThat(stub.requests().get(2).nameHeader()).isEqualTo("echo");
            assertThat(stub.requests().get(2).parameterHeader()).isEqualTo("=?base64?5YyX5Lqs?=");
        }
    }

    @Test
    void acceptsRequestScopedEventStreamResponses() throws Exception {
        try (ModernStubServer stub = new ModernStubServer(true)) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);

            client.initialize(List.of());
            assertThat(client.listTools(null, List.of()).tools())
                    .extracting(tool -> tool.name())
                    .containsExactly("echo");
            client.close();
        }
    }

    @Test
    void refusesCallingUndiscoveredToolBeforeDispatch() throws Exception {
        try (ModernStubServer stub = new ModernStubServer()) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);
            client.initialize(List.of());
            AtomicInteger dispatched = new AtomicInteger();

            assertThatThrownBy(() -> client.callTool("echo", Map.of(), List.of(), observer(dispatched)))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_TOOL_SCHEMA_NOT_DISCOVERED"));
            assertThat(dispatched).hasValue(0);
            assertThat(stub.methods()).containsExactly("server/discover");
            client.close();
        }
    }

    @Test
    void excludesToolWithInvalidHttpHeaderSchemaAnnotation() throws Exception {
        try (ModernStubServer stub = new ModernStubServer(false, true)) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);

            client.initialize(List.of());
            assertThat(client.listTools(null, List.of()).tools()).isEmpty();
            client.close();
        }
    }

    @Test
    void rejectsDiscoveryWhenServerDoesNotSupportPinned2026Version() throws Exception {
        try (ModernStubServer stub = new ModernStubServer(false, false, "2025-11-25")) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_PROTOCOL_VERSION_MISMATCH"));
        }
    }

    @Test
    void reportsFutureServerVersionAsPendingAdaptation() throws Exception {
        try (ModernStubServer stub = new ModernStubServer(false, false, "2026-08-01")) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessage("MCP 协议版本2026-08-01未适配，即将适配")
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_PROTOCOL_VERSION_PENDING_ADAPTATION"));
        }
    }

    @Test
    void reportsFutureVersionFromUnsupportedProtocolHttpResponse() throws Exception {
        try (ModernStubServer stub = new ModernStubServer(false, false, "2026-09-10", true)) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(
                                    stub.endpoint(), Set.of("echo"), McpProtocolProfile.FIXED_2026_07_28),
                            McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessage("MCP 协议版本2026-09-10未适配，即将适配")
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_PROTOCOL_VERSION_PENDING_ADAPTATION"));
        }
    }

    @Test
    void blocksConfiguredFutureVersionBeforeNetworkDispatchWithAdaptationNotice() {
        var definition = McpTestFixtures.httpServer(
                java.net.URI.create("http://127.0.0.1:1/mcp"), Set.of("echo"), new McpProtocolProfile("2027-01-15"));
        var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

        assertThatThrownBy(() -> client.initialize(List.of()))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessage("MCP 协议版本2027-01-15未适配，即将适配")
                .satisfies(error -> {
                    var invocation = (ToolInvocationException) error;
                    assertThat(invocation.failureCode()).isEqualTo("MCP_PROTOCOL_VERSION_PENDING_ADAPTATION");
                    assertThat(invocation.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
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

    private static final class ModernStubServer implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final List<RequestCapture> requests = new CopyOnWriteArrayList<>();
        private final boolean eventStream;
        private final boolean invalidHeader;
        private final String supportedVersion;
        private final boolean rejectDiscovery;

        private ModernStubServer() throws IOException {
            this(false, false, "2026-07-28", false);
        }

        private ModernStubServer(boolean eventStream) throws IOException {
            this(eventStream, false, "2026-07-28", false);
        }

        private ModernStubServer(boolean eventStream, boolean invalidHeader) throws IOException {
            this(eventStream, invalidHeader, "2026-07-28", false);
        }

        private ModernStubServer(boolean eventStream, boolean invalidHeader, String supportedVersion)
                throws IOException {
            this(eventStream, invalidHeader, supportedVersion, false);
        }

        private ModernStubServer(
                boolean eventStream, boolean invalidHeader, String supportedVersion, boolean rejectDiscovery)
                throws IOException {
            this.eventStream = eventStream;
            this.invalidHeader = invalidHeader;
            this.supportedVersion = supportedVersion;
            this.rejectDiscovery = rejectDiscovery;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        private java.net.URI endpoint() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private List<String> methods() {
            return requests.stream().map(RequestCapture::method).toList();
        }

        private List<RequestCapture> requests() {
            return List.copyOf(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            Map<String, Object> params = objectMap(request.get("params"));
            String method = String.valueOf(request.get("method"));
            requests.add(new RequestCapture(
                    method,
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                    exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
                    exchange.getRequestHeaders().getFirst("Mcp-Method"),
                    exchange.getRequestHeaders().getFirst("Mcp-Name"),
                    exchange.getRequestHeaders().getFirst("Mcp-Param-Region"),
                    objectMap(params.get("_meta"))));
            if (rejectDiscovery && method.equals("server/discover")) {
                byte[] body = mapper.writeValueAsBytes(Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        request.get("id"),
                        "error",
                        Map.of(
                                "code",
                                -32600,
                                "message",
                                "Unsupported protocol version",
                                "data",
                                Map.of("supportedVersions", List.of(supportedVersion)))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            Object result =
                    switch (method) {
                        case "server/discover" ->
                            Map.of(
                                    "resultType",
                                    "complete",
                                    "supportedVersions",
                                    List.of(supportedVersion),
                                    "capabilities",
                                    Map.of("tools", Map.of("listChanged", false)),
                                    "_meta",
                                    Map.of(
                                            "io.modelcontextprotocol/serverInfo",
                                            Map.of("name", "modern-stub", "version", "1.0.0")));
                        case "tools/list" -> Map.of("resultType", "complete", "tools", List.of(tool()));
                        case "tools/call" ->
                            Map.of(
                                    "resultType",
                                    "complete",
                                    "content",
                                    List.of(Map.of("type", "text", "text", "hello")),
                                    "structuredContent",
                                    Map.of("value", "hello"),
                                    "isError",
                                    false);
                        default -> throw new IllegalStateException(method);
                    };
            String json =
                    mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            byte[] body = eventStream
                    ? ("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)
                    : json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", eventStream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private Map<String, Object> tool() {
            Map<String, Object> inputSchema = invalidHeader
                    ? Map.of(
                            "type",
                            "object",
                            "oneOf",
                            List.of(Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("region", Map.of("type", "string", "x-mcp-header", "Region")))))
                    : Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "region", Map.of("type", "string", "x-mcp-header", "Region"),
                                    "value", Map.of("type", "string")));
            return Map.of(
                    "name",
                    "echo",
                    "description",
                    "echo",
                    "inputSchema",
                    inputSchema,
                    "outputSchema",
                    Map.of("type", "object"));
        }

        private Map<String, Object> objectMap(Object value) {
            return value == null ? Map.of() : mapper.convertValue(value, new TypeReference<>() {});
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record RequestCapture(
            String method,
            String httpMethod,
            String sessionHeader,
            String protocolHeader,
            String methodHeader,
            String nameHeader,
            String parameterHeader,
            Map<String, Object> meta) {}
}
