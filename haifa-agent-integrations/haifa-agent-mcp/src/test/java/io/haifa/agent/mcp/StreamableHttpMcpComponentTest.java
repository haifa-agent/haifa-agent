package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpTelemetry;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.transport.http.BoundedHttpClientBuilder;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void enforcesResponseBodyBudgetWithStableFailureCode() throws Exception {
        try (FaultStubServer stub = new FaultStubServer(Fault.OVERSIZE_INITIALIZE)) {
            var definition = McpTestFixtures.httpServer(
                    stub.endpoint(), Set.of("time_now"), java.time.Duration.ofSeconds(2), 1024, 16 * 1024);
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_HTTP_RESPONSE_BODY_TOO_LARGE"));
        }
        try (FaultStubServer stub = new FaultStubServer(Fault.OVERSIZE_HEADERS)) {
            var definition = McpTestFixtures.httpServer(
                    stub.endpoint(), Set.of("time_now"), java.time.Duration.ofSeconds(2), 1024 * 1024, 1024);
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_HTTP_RESPONSE_HEADERS_TOO_LARGE"));
        }
    }

    @Test
    void mapsMalformedAndTimedOutInitializeResponsesToStableFailure() throws Exception {
        try (FaultStubServer malformed = new FaultStubServer(Fault.MALFORMED_INITIALIZE)) {
            var client = new SdkMcpClientFactory()
                    .create(
                            McpTestFixtures.httpServer(malformed.endpoint(), Set.of("time_now")),
                            McpTestFixtures.IDENTITY);
            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_INITIALIZE_FAILED"));
        }
        try (FaultStubServer slow = new FaultStubServer(Fault.SLOW_INITIALIZE)) {
            var definition = McpTestFixtures.httpServer(
                    slow.endpoint(), Set.of("time_now"), java.time.Duration.ofMillis(50), 1024 * 1024, 16 * 1024);
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);
            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> assertThat(((ToolInvocationException) error).failureCode())
                            .isEqualTo("MCP_INITIALIZE_FAILED"));
        }
    }

    @Test
    void mapsAuthorizationResponsesToUnsupportedFlowOrReauthenticationWithoutLeakingSecrets() throws Exception {
        try (FaultStubServer stub = new FaultStubServer(Fault.AUTHORIZATION_REQUIRED)) {
            var client = new SdkMcpClientFactory()
                    .create(McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now")), McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of()))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> {
                        var invocation = (ToolInvocationException) error;
                        assertThat(invocation.failureCode()).isEqualTo("MCP_AUTH_FLOW_UNSUPPORTED");
                        assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.ACKNOWLEDGED);
                    });
        }
        try (FaultStubServer stub = new FaultStubServer(Fault.CREDENTIAL_REJECTED)) {
            var requirement = new CredentialRequirement(
                    new CredentialDefinitionId("utility-token"),
                    "utility authentication",
                    Set.of("mcp:tools:list", "mcp:tools:call"),
                    CredentialExposureMode.HTTP_HEADER);
            var injection = new McpCredentialInjection(requirement, "Authorization", "Bearer ");
            var definition = McpTestFixtures.withDiscoveryCredentials(
                    McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now")), List.of(injection));
            var lease = McpTestFixtures.lease("utility-token-binding", "never-log-this-secret");
            var client = new SdkMcpClientFactory().create(definition, McpTestFixtures.IDENTITY);

            assertThatThrownBy(() -> client.initialize(List.of(lease)))
                    .isInstanceOf(ToolInvocationException.class)
                    .satisfies(error -> {
                        var invocation = (ToolInvocationException) error;
                        assertThat(invocation.failureCode()).isEqualTo("MCP_REAUTH_REQUIRED");
                        assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.ACKNOWLEDGED);
                        assertThat(invocation)
                                .hasMessageNotContaining("never-log-this-secret")
                                .hasNoCause();
                    });
        }
    }

    @Test
    void invalidatesUnknownSessionAndReinitializesWithoutReplayingDispatchedCall() throws Exception {
        try (FaultStubServer stub = new FaultStubServer(Fault.SESSION_INVALID_ON_CALL)) {
            var definition = McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now"));
            try (var connections = new McpConnectionManager(List.of(definition), new SdkMcpClientFactory())) {
                var first = connections.acquire(
                        definition.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of());
                AtomicInteger dispatched = new AtomicInteger();

                assertThatThrownBy(() -> first.client().callTool("time_now", Map.of(), List.of(), observer(dispatched)))
                        .isInstanceOf(ToolInvocationException.class)
                        .satisfies(error -> {
                            var invocation = (ToolInvocationException) error;
                            assertThat(invocation.failureCode()).isEqualTo("MCP_SESSION_INVALID");
                            assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.OUTCOME_UNKNOWN);
                        });
                var second = connections.acquire(
                        definition.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of());

                assertThat(second).isNotSameAs(first);
                assertThat(stub.initializeCount()).isGreaterThanOrEqualTo(2);
                assertThat(stub.callCount()).isEqualTo(1);
                assertThat(dispatched).hasValue(1);
            }
        }
    }

    @Test
    void handlesSessionNotificationStreamAndDelete405() throws Exception {
        try (FaultStubServer stub = new FaultStubServer(Fault.RESUMABLE_NOTIFICATION)) {
            List<String> notifications = new CopyOnWriteArrayList<>();
            CountDownLatch notified = new CountDownLatch(1);
            var definition = McpTestFixtures.httpServer(stub.endpoint(), Set.of("time_now"));
            var client = new SdkMcpClientFactory(serverId -> {
                        notifications.add(serverId);
                        notified.countDown();
                    })
                    .create(definition, McpTestFixtures.IDENTITY);

            client.initialize(List.of());
            assertThat(notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stub.awaitResumedGet()).isTrue();
            client.close();

            assertThat(notifications).contains(definition.serverId().value());
            assertThat(stub.getCount()).isGreaterThanOrEqualTo(2);
            assertThat(stub.lastEventIds()).contains("event-1");
            assertThat(stub.deleteCount()).isEqualTo(1);
        }
    }

    @Test
    void retainsLastEventIdWhenSdkResumableStreamReconnectsAfterFailure() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> resumedFrom =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch reconnected = new CountDownLatch(1);
        var stream = new io.modelcontextprotocol.spec.DefaultMcpTransportStream<Void>(true, current -> {
            resumedFrom.set(current.lastId().orElse(null));
            reconnected.countDown();
            return reactor.core.publisher.Mono.empty();
        });
        var notification =
                new io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification("notifications/tools/list_changed");
        var item = reactor.util.function.Tuples.of(
                java.util.Optional.of("event-1"), (Iterable<io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage>)
                        List.<io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage>of(notification));
        var input = reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just(item),
                reactor.core.publisher.Flux.error(new IOException("stream interrupted")));

        reactor.core.publisher.Flux.from(stream.consumeSseStream(input))
                .onErrorComplete()
                .blockLast(java.time.Duration.ofSeconds(1));

        assertThat(reconnected.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(resumedFrom).hasValue("event-1");
    }

    @Test
    void doesNotReplayCredentialHeadersWhenGetStreamEnds() throws Exception {
        try (FaultStubServer stub = new FaultStubServer(Fault.RESUMABLE_NOTIFICATION)) {
            HttpClient client = new BoundedHttpClientBuilder(
                            HttpClient.newBuilder(), 1024 * 1024, 16 * 1024, 2, Duration.ZERO, Set.of("Authorization"))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(stub.endpoint())
                    .header("Authorization", "Bearer test-placeholder")
                    .GET()
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

            assertThat(stub.getCount()).isEqualTo(1);
            assertThat(stub.lastEventIds()).isEmpty();
        }
    }

    private enum Fault {
        OVERSIZE_INITIALIZE,
        OVERSIZE_HEADERS,
        MALFORMED_INITIALIZE,
        SLOW_INITIALIZE,
        AUTHORIZATION_REQUIRED,
        CREDENTIAL_REJECTED,
        SESSION_INVALID_ON_CALL,
        RESUMABLE_NOTIFICATION
    }

    private static final class FaultStubServer implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final Fault fault;
        private final AtomicInteger initializeCount = new AtomicInteger();
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger getCount = new AtomicInteger();
        private final AtomicInteger deleteCount = new AtomicInteger();
        private final List<String> lastEventIds = new CopyOnWriteArrayList<>();
        private final CountDownLatch resumedGet = new CountDownLatch(1);

        private FaultStubServer(Fault fault) throws IOException {
            this.fault = fault;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        private java.net.URI endpoint() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private int initializeCount() {
            return initializeCount.get();
        }

        private int callCount() {
            return callCount.get();
        }

        private int getCount() {
            return getCount.get();
        }

        private int deleteCount() {
            return deleteCount.get();
        }

        private List<String> lastEventIds() {
            return List.copyOf(lastEventIds);
        }

        private boolean awaitResumedGet() throws InterruptedException {
            return resumedGet.await(2, TimeUnit.SECONDS);
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGet(exchange);
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteCount.incrementAndGet();
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            if (!request.containsKey("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if ("initialize".equals(method)) {
                handleInitialize(exchange, request);
                return;
            }
            if ("tools/call".equals(method)) {
                callCount.incrementAndGet();
                if (fault == Fault.SESSION_INVALID_ON_CALL && "session-1".equals(session(exchange))) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
            }
            respond(
                    exchange,
                    request.get("id"),
                    Map.of(
                            "content", List.of(Map.of("type", "text", "text", "ok")),
                            "structuredContent", Map.of(),
                            "isError", false));
        }

        private void handleInitialize(HttpExchange exchange, Map<String, Object> request) throws IOException {
            int number = initializeCount.incrementAndGet();
            if (fault == Fault.AUTHORIZATION_REQUIRED || fault == Fault.CREDENTIAL_REJECTED) {
                exchange.sendResponseHeaders(fault == Fault.AUTHORIZATION_REQUIRED ? 401 : 403, -1);
                exchange.close();
                return;
            }
            if (fault == Fault.SLOW_INITIALIZE) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fault == Fault.MALFORMED_INITIALIZE) {
                byte[] body = "{not-json".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            String name = fault == Fault.OVERSIZE_INITIALIZE ? "x".repeat(2048) : "fault-stub";
            if (fault == Fault.SESSION_INVALID_ON_CALL || fault == Fault.RESUMABLE_NOTIFICATION) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session-" + number);
            }
            if (fault == Fault.OVERSIZE_HEADERS) exchange.getResponseHeaders().set("X-Mcp-Large", "x".repeat(2048));
            respond(
                    exchange,
                    request.get("id"),
                    Map.of(
                            "protocolVersion", "2025-11-25",
                            "capabilities", Map.of("tools", Map.of("listChanged", true)),
                            "serverInfo", Map.of("name", name, "version", "1.0.0")));
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            int number = getCount.incrementAndGet();
            if (number >= 2) resumedGet.countDown();
            String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            if (lastEventId != null) lastEventIds.add(lastEventId);
            if (fault != Fault.RESUMABLE_NOTIFICATION) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if (number <= 2) {
                String notification = mapper.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "method", "notifications/tools/list_changed"));
                byte[] body = ("id: event-" + number + "\nevent: message\ndata: " + notification + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }

        private String session(HttpExchange exchange) {
            return exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        }

        private void respond(HttpExchange exchange, Object id, Object result) throws IOException {
            byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
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
