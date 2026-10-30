package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.project.domain.ProjectId;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground loopback CA Host over one trusted Coding product assembly. */
final class CodingHostServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int COMMAND_PERMITS = 8;
    private static final int CONTROL_PERMITS = 4;

    private final StandaloneCodingAgent agent;
    private final CodingSessionClient client;
    private final ProjectId projectId;
    private final CodingHostDispatcher dispatcher;
    private final CodingHostSubscriptions subscriptions;
    private final CodingHostClients clients;
    private final CodingHostMaintenance maintenance;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Semaphore commandPermits = new Semaphore(COMMAND_PERMITS);
    private final Semaphore controlPermits = new Semaphore(CONTROL_PERMITS);
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Object> recoveryReport;

    private CodingHostServer(StandaloneCodingAgent agent, InetAddress bind, int port) throws IOException {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.client = agent.client();
        this.projectId = agent.projectId();
        LocalCodingAgent local = agent.localAgent();
        this.subscriptions = new CodingHostSubscriptions(
                client,
                CodingHostSubscriptions.DEFAULT_MAXIMUM_SUBSCRIPTIONS,
                CodingHostSubscriptions.DEFAULT_QUEUE_CAPACITY,
                local.time(),
                CodingHostSubscriptions.DEFAULT_ATTACH_TIMEOUT,
                local.identifiers());
        this.clients = new CodingHostClients(
                local.identifiers(),
                local.time(),
                Duration.ofMinutes(30),
                CodingHostClients.DEFAULT_MAXIMUM_CLIENTS,
                subscriptions::closeClient);
        this.maintenance = new CodingHostMaintenance(projectId, local.codingSessions(), client);
        this.dispatcher = new CodingHostDispatcher(
                projectId,
                client,
                local.codingSessions(),
                local.runtime(),
                local.outcomes(),
                agent.authentication(),
                subscriptions);
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            server.setExecutor(executor);
            server.createContext("/v1/rpc", this::handleRpc);
            server.createContext("/v1/events", this::handleEvents);
            server.createContext("/health", this::handleHealth);
            this.recoveryReport = recoverSessions(local.codingSessions(), local.runtime());
            server.start();
            maintenance.start();
        } catch (RuntimeException | Error exception) {
            server.stop(0);
            maintenance.close();
            executor.shutdownNow();
            subscriptions.closeAll();
            clients.close();
            throw exception;
        }
    }

    static CodingHostServer start(Path workspace, Optional<Path> config, String bind, int port) {
        CliArguments cli = new CliArguments(
                Optional.empty(),
                Optional.empty(),
                Optional.of(workspace),
                config,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                false,
                false);
        CliConfiguration configuration = new CliConfigurationLoader().loadForHost(cli, workspace);
        if (configuration.persistence().mode()
                == io.haifa.agent.application.project.persistence.ProjectPersistenceMode.MEMORY) {
            throw new IllegalStateException("HOST_DURABLE_PERSISTENCE_REQUIRED: Host requires SQLITE persistence");
        }
        StandaloneCodingAgent agent;
        try {
            agent = StandaloneCodingAgents.open(
                    workspace, configuration, new PrintStream(OutputStream.nullOutputStream()), ignored -> {});
        } catch (io.haifa.agent.application.project.persistence.CodingDatabaseOwnershipException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        return start(agent, bind, port);
    }

    static CodingHostServer start(StandaloneCodingAgent agent, String bind, int port) {
        try {
            return new CodingHostServer(agent, loopback(bind), port);
        } catch (IOException exception) {
            agent.close();
            throw new IllegalStateException("CA Host listener failed to start", exception);
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }

    static InetAddress loopback(String bind) {
        String value = Objects.requireNonNull(bind, "bind must not be null").trim();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(value);
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalArgumentException("HOST_BIND_MUST_BE_LOOPBACK: " + value);
                }
            }
            if (addresses.length == 0) throw new IllegalArgumentException("HOST_BIND_MUST_BE_LOOPBACK: " + value);
            return addresses[0];
        } catch (IOException exception) {
            throw new IllegalArgumentException("HOST_BIND_INVALID: " + value, exception);
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    Map<String, Object> recoveryReport() {
        return recoveryReport;
    }

    void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/v1/rpc") || rejectMethod(exchange, "POST")) return;
        if (!isJsonContentType(exchange)) {
            writeError(exchange, null, 415, -32600, "UNSUPPORTED_MEDIA_TYPE", "application/json is required");
            return;
        }
        JsonNode id = null;
        try (exchange) {
            byte[] body = readBounded(exchange);
            if (body.length > MAX_REQUEST_BYTES) {
                writeError(exchange, id, 413, -32600, "PAYLOAD_TOO_LARGE", "Request body is too large");
                return;
            }
            JsonNode request;
            try {
                request = json.readTree(body);
            } catch (IOException exception) {
                writeError(exchange, id, 200, -32700, "PARSE_ERROR", "Invalid JSON");
                return;
            }
            if (request == null
                    || !request.isObject()
                    || !request.path("jsonrpc").isTextual()
                    || !"2.0".equals(request.path("jsonrpc").textValue())) {
                writeError(
                        exchange,
                        request == null ? null : request.get("id"),
                        200,
                        -32600,
                        "INVALID_REQUEST",
                        "Invalid JSON-RPC request");
                return;
            }
            id = request.get("id");
            if (id != null
                    && !id.isNull()
                    && !id.isTextual()
                    && !id.isIntegralNumber()
                    && !id.isFloatingPointNumber()) {
                writeError(exchange, null, 200, -32600, "INVALID_REQUEST", "id must be string, number, or null");
                return;
            }
            if (!request.hasNonNull("method")
                    || !request.path("method").isTextual()
                    || request.path("method").textValue().isBlank()) {
                writeError(exchange, id, 200, -32600, "INVALID_REQUEST", "method is required");
                return;
            }
            String method = request.path("method").textValue();
            Semaphore permits = controlMethod(method) ? controlPermits : commandPermits;
            try {
                if (!permits.tryAcquire(5, TimeUnit.SECONDS)) {
                    writeError(exchange, id, 503, -32000, "HOST_BUSY", "Host command capacity is exhausted");
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                writeError(exchange, id, 503, -32000, "HOST_BUSY", "Host command admission was interrupted");
                return;
            }
            try {
                String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
                if (method.equals("initialize")) {
                    Object result = dispatcher.dispatch(null, method, request.get("params"));
                    Map<String, Object> initialized = new LinkedHashMap<>();
                    if (result instanceof Map<?, ?> values) {
                        values.forEach((key, value) -> initialized.put(String.valueOf(key), value));
                    }
                    initialized.put("clientId", clients.initialize());
                    writeResult(exchange, id, initialized);
                } else {
                    if (method.equals("host/ping")) {
                        writeResult(exchange, id, dispatcher.dispatch(null, method, request.get("params")));
                    } else {
                        if (clientId == null || clientId.isBlank()) {
                            writeError(
                                    exchange, id, 401, -32000, "CLIENT_CONTEXT_REQUIRED", "X-CA-Client-ID is required");
                            return;
                        }
                        clients.require(clientId);
                        writeResult(exchange, id, dispatcher.dispatch(clientId, method, request.get("params")));
                    }
                }
            } catch (CodingHostException exception) {
                writeError(
                        exchange,
                        id,
                        statusFor(exception),
                        exception.jsonRpcCode(),
                        exception.reasonCode(),
                        exception.getMessage());
            } catch (RuntimeException exception) {
                writeError(exchange, id, 500, -32603, "INTERNAL_ERROR", "Host command failed safely");
            } finally {
                permits.release();
            }
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/v1/events") || rejectMethod(exchange, "GET"))
            return;
        String subscriptionId = query(exchange.getRequestURI()).get("subscriptionId");
        if (subscriptionId == null || subscriptionId.isBlank()) {
            writeError(exchange, null, 400, -32602, "INVALID_PARAMS", "subscriptionId is required");
            return;
        }
        String clientId = exchange.getRequestHeaders().getFirst("X-CA-Client-ID");
        if (clientId == null || clientId.isBlank()) {
            writeError(exchange, null, 401, -32000, "CLIENT_CONTEXT_REQUIRED", "X-CA-Client-ID is required");
            return;
        }
        CodingHostSubscriptions.Subscription subscription;
        try {
            clients.require(clientId);
            subscription = subscriptions.attach(clientId, subscriptionId);
        } catch (CodingHostException exception) {
            writeError(
                    exchange,
                    null,
                    statusFor(exception),
                    exception.jsonRpcCode(),
                    exception.reasonCode(),
                    exception.getMessage());
            return;
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                while (!subscription.closed()) {
                    Map<String, Object> value = subscription.poll(2, TimeUnit.SECONDS);
                    if (value == null) {
                        output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        continue;
                    }
                    byte[] payload = json.writeValueAsBytes(value);
                    output.write(("event: " + subscription.type() + "\ndata: ").getBytes(StandardCharsets.UTF_8));
                    output.write(payload);
                    output.write("\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Disconnect closes the observation only; the Run continues.
        } finally {
            subscriptions.unsubscribe(clientId, subscriptionId);
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (rejectBrowserOrigin(exchange) || rejectPath(exchange, "/health") || rejectMethod(exchange, "GET")) return;
        try (exchange) {
            byte[] payload = json.writeValueAsBytes(
                    Map.of("status", "ok", "protocolVersion", "1", "host", "ca-host", "previewAvailable", false));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static boolean isJsonContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mime = (separator >= 0 ? contentType.substring(0, separator) : contentType).trim();
        return mime.equalsIgnoreCase("application/json");
    }

    private boolean rejectPath(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestURI().getPath())) return false;
        writeError(exchange, null, 404, -32600, "PATH_NOT_FOUND", "Unsupported Host path");
        return true;
    }

    private boolean rejectBrowserOrigin(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Origin") == null) return false;
        writeError(exchange, null, 403, -32000, "BROWSER_ORIGIN_REJECTED", "Browser origins are not accepted");
        return true;
    }

    private boolean rejectMethod(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return false;
        writeError(exchange, null, 405, -32600, "METHOD_NOT_ALLOWED", "Unsupported HTTP method");
        return true;
    }

    private byte[] readBounded(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        return body;
    }

    private void writeResult(HttpExchange exchange, JsonNode id, Object result) throws IOException {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        else response.putNull("id");
        response.set("result", json.valueToTree(result));
        write(exchange, 200, response);
    }

    private void writeError(HttpExchange exchange, JsonNode id, int status, int code, String reasonCode, String message)
            throws IOException {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        else response.putNull("id");
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? reasonCode : message);
        ObjectNode data = error.putObject("data");
        data.put("reasonCode", reasonCode);
        write(exchange, status, response);
    }

    private void write(HttpExchange exchange, int status, ObjectNode response) throws IOException {
        byte[] payload = json.writeValueAsBytes(response);
        if (payload.length > MAX_RESPONSE_BYTES) {
            response = json.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.putNull("id");
            ObjectNode error = response.putObject("error");
            error.put("code", -32000);
            error.put("message", "Response is too large");
            error.putObject("data").put("reasonCode", "PAYLOAD_TOO_LARGE");
            payload = json.writeValueAsBytes(response);
            status = 200;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static int statusFor(CodingHostException exception) {
        return exception.reasonCode().contains("NOT_FOUND") ? 404 : exception.jsonRpcCode() == -32602 ? 400 : 409;
    }

    private static boolean controlMethod(String method) {
        return method.equals("run/cancel")
                || method.equals("interaction/respond")
                || method.equals("approval/prepare")
                || method.startsWith("auth/");
    }

    private Map<String, Object> recoverSessions(
            io.haifa.agent.application.project.product.coding.CodingSessionService sessions,
            io.haifa.agent.runtime.api.AgentRuntime runtime) {
        List<String> settled = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int scanned = 0;
        boolean scanTruncated = false;
        try {
            var page = sessions.listSessionsForRecovery(projectId, 100);
            scanTruncated = page.hasMore();
            for (CodingSessionSummary summary : page.items()) {
                scanned++;
                Optional<AgentRunId> active = summary.activeRunId();
                if (active.isEmpty()) continue;
                try {
                    var reconciled = client.reconcile(summary.sessionId());
                    boolean stillActive = reconciled
                            .activeRun()
                            .map(run -> run.runId().equals(active.orElseThrow()))
                            .orElse(false);
                    boolean terminal = runtime.find(active.orElseThrow())
                            .filter(snapshot -> snapshot.status().isTerminal())
                            .isPresent();
                    if (!stillActive && terminal)
                        settled.add(active.orElseThrow().value());
                } catch (RuntimeException exception) {
                    errors.add(safeErrorCode(exception));
                }
            }
        } catch (RuntimeException exception) {
            errors.add(safeErrorCode(exception));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedSessions", scanned);
        report.put("scanLimit", 100);
        report.put("scanTruncated", scanTruncated);
        report.put("settledRunIds", settled);
        report.put("errors", errors);
        report.put(
                "note",
                "Existing product recovery semantics; no automatic replay of uncertain tool/shell side effects");
        return report;
    }

    private static String safeErrorCode(RuntimeException exception) {
        if (exception instanceof io.haifa.agent.application.project.product.ProjectProductException product) {
            return product.code();
        }
        if (exception instanceof io.haifa.agent.runtime.api.RuntimeContractException contract) {
            return contract.code().name();
        }
        return "RECOVERY_SCAN_FAILED";
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return values;
        for (String entry : raw.split("&")) {
            int separator = entry.indexOf('=');
            if (separator < 0) continue;
            values.put(
                    URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.stop(0);
        } finally {
            try {
                maintenance.close();
                subscriptions.closeAll();
                clients.close();
            } finally {
                executor.shutdownNow();
                try {
                    agent.close();
                } finally {
                    terminated.countDown();
                }
            }
        }
    }
}
