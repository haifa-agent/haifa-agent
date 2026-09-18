package io.haifa.agent.personalassistant.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test-only loopback MCP server that exposes the reviewed {@code echo} Tool.
 *
 * <p>The shipped product does not host an embedded MCP stub. Integration tests therefore start this server
 * themselves and point the product at it through an explicit external {@code haifa.personal.mcp} configuration.
 */
public final class PersonalMcpTestServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FIRST_PORT = 22001;
    private static final int LAST_PORT = 22100;

    private final HttpServer server;
    private final ExecutorService executor;

    private PersonalMcpTestServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static PersonalMcpTestServer start() {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            HttpServer candidate;
            try {
                candidate = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            } catch (IOException ignored) {
                continue;
            }
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            candidate.createContext("/mcp", PersonalMcpTestServer::handle);
            candidate.setExecutor(executor);
            candidate.start();
            return new PersonalMcpTestServer(candidate, executor);
        }
        throw new IllegalStateException(
                "no free Personal MCP test port between " + FIRST_PORT + " and " + LAST_PORT);
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> request = MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            if (!request.containsKey("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            String method = String.valueOf(request.get("method"));
            Object result =
                    switch (method) {
                        case "initialize" ->
                            Map.of(
                                    "protocolVersion",
                                    "2025-11-25",
                                    "capabilities",
                                    Map.of("tools", Map.of("listChanged", false)),
                                    "serverInfo",
                                    Map.of("name", "haifa-personal-test-mcp", "version", "1.0.0"));
                        case "tools/list" ->
                            Map.of(
                                    "tools",
                                    List.of(Map.of(
                                            "name",
                                            "echo",
                                            "title",
                                            "Local echo",
                                            "description",
                                            "Return bounded text through the local MCP pipeline",
                                            "inputSchema",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of(
                                                            "text",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "minLength",
                                                                    1,
                                                                    "maxLength",
                                                                    512)),
                                                    "required",
                                                    List.of("text"),
                                                    "additionalProperties",
                                                    false),
                                            "outputSchema",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of("text", Map.of("type", "string")),
                                                    "required",
                                                    List.of("text"),
                                                    "additionalProperties",
                                                    false))));
                        case "tools/call" -> call(request);
                        default -> throw new IllegalArgumentException("unsupported MCP method");
                    };
            byte[] response =
                    MAPPER.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (IllegalArgumentException exception) {
            byte[] response =
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"invalid request\"}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }

    private static Object call(Map<String, Object> request) {
        Object paramsValue = request.get("params");
        if (!(paramsValue instanceof Map<?, ?> params) || !"echo".equals(String.valueOf(params.get("name")))) {
            throw new IllegalArgumentException("only echo is allowed");
        }
        Object argumentsValue = params.get("arguments");
        if (!(argumentsValue instanceof Map<?, ?> arguments)) {
            throw new IllegalArgumentException("arguments are required");
        }
        String text = String.valueOf(arguments.get("text")).trim();
        if (text.isEmpty() || text.length() > 512) throw new IllegalArgumentException("invalid echo text");
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", "Echo completed")),
                "structuredContent", Map.of("text", text),
                "isError", false);
    }
}
