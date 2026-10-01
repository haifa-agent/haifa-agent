package io.haifa.example.sdk.advanced;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal offline Streamable HTTP MCP server used by the external-package assembly test.
 *
 * <p>It binds an OS-assigned loopback port and implements only the MCP handshake, {@code tools/list}
 * and {@code tools/call} frames the production {@code SdkMcpClientFactory} path needs. It is a test
 * double for the remote server, never for the client: the client always runs through the SDK
 * production factory inside {@code McpToolPlatforms}.
 */
final class LoopbackMcpServer implements AutoCloseable {
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final String displayName;
    private final List<String> remoteToolNames;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger deleteCount = new AtomicInteger();

    LoopbackMcpServer(String displayName, String[] remoteToolNames) throws IOException {
        this.displayName = displayName;
        this.remoteToolNames = List.of(remoteToolNames);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    int deleteCount() {
        return deleteCount.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> sendStatus(exchange, 405);
                case "DELETE" -> {
                    deleteCount.incrementAndGet();
                    sendStatus(exchange, 200);
                }
                case "POST" -> handlePost(exchange);
                default -> sendStatus(exchange, 405);
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
        Object method = request.get("method");
        if (!request.containsKey("id")) {
            sendStatus(exchange, 202);
            return;
        }
        if ("initialize".equals(method)) {
            handleInitialize(exchange, request);
            return;
        }
        if ("tools/list".equals(method)) {
            respond(exchange, request.get("id"), Map.of("tools", toolSpecifications()));
            return;
        }
        if ("tools/call".equals(method)) {
            handleCall(exchange, request);
            return;
        }
        respond(exchange, request.get("id"), Map.of());
    }

    private void handleInitialize(HttpExchange exchange, Map<String, Object> request) throws IOException {
        // A session id makes the production Streamable HTTP transport track the session and send a
        // DELETE on graceful close, which is how the tests observe connection release.
        exchange.getResponseHeaders().set("Mcp-Session-Id", "loopback-session-1");
        respond(
                exchange,
                request.get("id"),
                Map.of(
                        "protocolVersion",
                        "2025-11-25",
                        "capabilities",
                        Map.of("tools", Map.of("listChanged", true)),
                        "serverInfo",
                        Map.of("name", displayName, "version", "1.0.0")));
    }

    private void handleCall(HttpExchange exchange, Map<String, Object> request) throws IOException {
        Map<String, Object> params = mapper.convertValue(request.get("params"), new TypeReference<>() {});
        String toolName = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> value
                ? mapper.convertValue(value, new TypeReference<>() {})
                : Map.of();
        calls.add(displayName + ":" + toolName + ":" + arguments.get("name"));
        respond(
                exchange,
                request.get("id"),
                Map.of(
                        "content", List.of(Map.of("type", "text", "text", "status-ok")),
                        "structuredContent", Map.of("status", "ok"),
                        "isError", false));
    }

    private List<Map<String, Object>> toolSpecifications() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : remoteToolNames) {
            tools.add(Map.of(
                    "name",
                    name,
                    "description",
                    "Remote " + name,
                    "inputSchema",
                    Map.of(
                            "$schema",
                            DRAFT_2020_12,
                            "type",
                            "object",
                            "properties",
                            Map.of("name", Map.of("type", "string")),
                            "required",
                            List.of("name"),
                            "additionalProperties",
                            false),
                    "outputSchema",
                    Map.of("$schema", DRAFT_2020_12, "type", "object", "additionalProperties", true)));
        }
        return tools;
    }

    private void respond(HttpExchange exchange, Object id, Object result) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("result", result);
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
