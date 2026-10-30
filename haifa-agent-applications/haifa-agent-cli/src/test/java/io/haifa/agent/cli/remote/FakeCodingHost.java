package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Minimal programmable loopback CA Host used by the remote client tests. */
final class FakeCodingHost implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();

    record RpcCall(String method, JsonNode params, String clientId, JsonNode request) {}

    record SseCall(String subscriptionId, String clientId) {}

    record RpcError(int code, String reasonCode, String message) {}

    record Reply(JsonNode result, RpcError error, boolean drop, String rawBody, String contentType) {
        static Reply result(Object value) {
            return new Reply(JSON.valueToTree(value), null, false, null, null);
        }

        static Reply nullResult() {
            return new Reply(JSON.nullNode(), null, false, null, null);
        }

        static Reply error(String reasonCode) {
            return error(reasonCode, "fake host error");
        }

        static Reply error(String reasonCode, String message) {
            return new Reply(null, new RpcError(-32009, reasonCode, message), false, null, null);
        }

        static Reply dropped() {
            return new Reply(null, null, true, null, null);
        }

        static Reply raw(String body) {
            return new Reply(null, null, false, body, "application/json");
        }

        Reply contentType(String value) {
            return new Reply(result, error, drop, rawBody, value);
        }
    }

    @FunctionalInterface
    interface Responder {
        Reply respond(RpcCall call);
    }

    private record SseScript(List<String> frames, boolean closeAfterFrames, CountDownLatch release) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Responder responder;
    private final List<RpcCall> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<SseCall> sseCalls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, SseScript> scripts = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> unsubscribeLatches = new ConcurrentHashMap<>();

    private FakeCodingHost(HttpServer server, Responder responder) {
        this.server = server;
        this.responder = responder;
    }

    static FakeCodingHost start(Responder responder) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeCodingHost host = new FakeCodingHost(server, responder);
        server.createContext("/v1/rpc", host::handleRpc);
        server.createContext("/v1/events", host::handleEvents);
        server.setExecutor(host.executor);
        server.start();
        return host;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<RpcCall> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    List<SseCall> sseCalls() {
        synchronized (sseCalls) {
            return List.copyOf(sseCalls);
        }
    }

    List<RpcCall> calls(String method) {
        return calls().stream().filter(call -> call.method().equals(method)).toList();
    }

    void script(String subscriptionId, List<String> frames, boolean closeAfterFrames) {
        scripts.put(subscriptionId, new SseScript(List.copyOf(frames), closeAfterFrames, new CountDownLatch(1)));
        unsubscribeLatches.putIfAbsent(subscriptionId, new CountDownLatch(1));
    }

    boolean awaitUnsubscribe(String subscriptionId) throws InterruptedException {
        CountDownLatch latch = unsubscribeLatches.get(subscriptionId);
        return latch != null && latch.await(5, TimeUnit.SECONDS);
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        JsonNode request;
        try (exchange) {
            request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode id = request == null ? null : request.get("id");
            String method = request == null || request.get("method") == null
                    ? ""
                    : request.get("method").textValue();
            JsonNode params = request == null ? null : request.get("params");
            String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
            calls.add(new RpcCall(method, params, clientId, request));
            if (method.equals("events/unsubscribe") || method.equals("output/unsubscribe")) {
                JsonNode subscriptionId = params == null ? null : params.get("subscriptionId");
                if (subscriptionId != null && subscriptionId.isTextual()) {
                    CountDownLatch latch = unsubscribeLatches.get(subscriptionId.textValue());
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }
            Reply reply;
            try {
                reply = responder.respond(new RpcCall(method, params, clientId, request));
            } catch (RuntimeException exception) {
                reply = Reply.error("FAKE_HOST_FAILURE");
            }
            if (reply.drop()) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 1024);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write("{\"partial\":".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                return;
            }
            if (reply.rawBody() != null) {
                byte[] payload = reply.rawBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders()
                        .set("Content-Type", reply.contentType() == null ? "application/json" : reply.contentType());
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
                return;
            }
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id == null) {
                response.putNull("id");
            } else {
                response.set("id", id);
            }
            if (reply.error() != null) {
                ObjectNode error = response.putObject("error");
                error.put("code", reply.error().code());
                error.put("message", reply.error().message());
                error.putObject("data").put("reasonCode", reply.error().reasonCode());
            } else {
                response.set("result", reply.result());
            }
            byte[] payload = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders()
                    .set("Content-Type", reply.contentType() == null ? "application/json" : reply.contentType());
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI());
        String subscriptionId = query.get("subscriptionId");
        String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
        sseCalls.add(new SseCall(subscriptionId, clientId));
        SseScript script = scripts.get(subscriptionId);
        if (script == null) {
            byte[] payload = "{\"error\":\"missing\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            for (String frame : script.frames()) {
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            if (!script.closeAfterFrames()) {
                try {
                    script.release().await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException ignored) {
            // The client closed its own observation.
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String entry : raw.split("&")) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                continue;
            }
            values.put(
                    URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    static Map<String, Object> initializeResult(String clientId, String projectId) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (String capability : List.of(
                "session-list",
                "session-create",
                "session-open",
                "session-history",
                "run-start",
                "run-steer",
                "run-follow-up",
                "run-cancel",
                "interaction",
                "event-stream",
                "output-stream",
                "shell",
                "export",
                "models",
                "workspace",
                "resources",
                "auth-status",
                "logout")) {
            capabilities.put(capability, true);
        }
        capabilities.put("preview", false);
        capabilities.put("session-consent", false);
        capabilities.put("auth-mutation", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "1");
        result.put("clientId", clientId);
        result.put("hostName", "ca-host");
        result.put("projectId", projectId);
        result.put("operator", Map.of("principalType", "local-user"));
        result.put("capabilities", capabilities);
        result.put("unsupported", List.of("preview", "session-consent", "credential-write", "credential-login"));
        return result;
    }

    @Override
    public void close() {
        for (SseScript script : scripts.values()) {
            script.release().countDown();
        }
        server.stop(0);
        executor.shutdownNow();
    }
}
