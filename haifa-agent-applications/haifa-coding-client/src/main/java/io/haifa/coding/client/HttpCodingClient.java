package io.haifa.coding.client;

import com.fasterxml.jackson.databind.*;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One negotiated native context; mutations are sent once and unknown outcomes are never replayed. */
final class HttpCodingClient implements CodingClient {
    private static final Set<String> OFFERED =
            Set.of("coding-product-v1", "session-presentation-v1", "session-consent-v1", "preview-v1");
    private static final Set<String> REQUIRED = Set.of("coding-product-v1", "session-presentation-v1");
    private static final int RESPONSE_BYTES = 1024 * 1024, REQUEST_BYTES = 256 * 1024;
    private final ObjectMapper json = new ObjectMapper()
            .enable(
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ClientOptions options;
    private final URI endpoint;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Observation> observations = ConcurrentHashMap.newKeySet();
    private final Set<PreviewObservation> previews = ConcurrentHashMap.newKeySet();
    private volatile ConnectionInfo connection;
    private volatile String clientId;
    private volatile boolean canCloseClient;

    private HttpCodingClient(ClientOptions options) {
        this.options = options;
        endpoint = loopback(options.endpoint());
    }

    static CodingClient connect(ClientOptions options) {
        var client = new HttpCodingClient(options);
        try {
            client.initialize(null);
            return client;
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    private static URI loopback(URI uri) {
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("Loopback Host base URI required");
        try {
            var addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !a.isLoopbackAddress()))
                throw new IllegalArgumentException("Loopback Host required");
        } catch (UnknownHostException invalid) {
            throw new IllegalArgumentException("Invalid Host address");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    private synchronized void initialize(String expectedProject) {
        JsonNode result = call(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "1",
                        "clientInfo",
                        Map.of("name", options.name(), "version", options.version()),
                        "optionalCapabilities",
                        OFFERED),
                false,
                false);
        clientId = text(result, "clientId");
        Set<String> selected = new HashSet<>();
        var optional = result.path("optionalCapabilities");
        if (!optional.isArray() || optional.size() > 32) throw failure("PROTOCOL_INCOMPATIBLE", false);
        for (var value : optional) {
            if (!value.isTextual() || !selected.add(value.textValue()) || !OFFERED.contains(value.textValue()))
                throw failure("PROTOCOL_INCOMPATIBLE", false);
        }
        canCloseClient = selected.contains("session-consent-v1");
        if (!text(result, "protocolVersion").equals("1") || !selected.containsAll(REQUIRED))
            throw failure("PRODUCT_CAPABILITY_UNSUPPORTED", false);
        String project = text(result, "projectId");
        if (expectedProject != null && !expectedProject.equals(project)) throw failure("HOST_PROJECT_CHANGED", false);
        connection = new ConnectionInfo(
                text(result, "hostIncarnation"),
                clientId,
                project,
                "1",
                text(result, "displayName"),
                selected.stream().sorted().toList(),
                "native");
    }

    private void requireOpen() {
        if (closed.get()) throw failure("CLIENT_CLOSED", false);
    }

    @Override
    public ConnectionInfo connection() {
        requireOpen();
        return connection;
    }

    @Override
    public QueryResult query(Query query) {
        requireOpen();
        return decode(call("product/query", query, false, true), QueryResult.class);
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        requireOpen();
        CodingProtocolChecks.command(request.command());
        try {
            JsonNode value = call("product/command", request, true, true);
            try {
                return decode(value, CommandResult.class);
            } catch (CodingClientException invalid) {
                throw failure("COMMAND_REPLY_INVALID", true);
            }
        } catch (CodingClientException failure) {
            if (!failure.outcomeUnknown()) throw failure;
            return new CommandResult(
                    new Receipt(request.command().clientRequestId(), "unknown", null, null, "COMMAND_OUTCOME_UNKNOWN"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    @Override
    public HistoryPage history(String sessionId, int limit, Long before) {
        requireOpen();
        var params = new LinkedHashMap<String, Object>();
        params.put("sessionId", sessionId);
        params.put("limit", limit);
        if (before != null) params.put("beforeSequence", before);
        var page = decode(call("presentation/history", params, false, true), HistoryPage.class);
        if (!page.sessionId().equals(sessionId)) throw failure("SESSION_SCOPE_MISMATCH", false);
        return page;
    }

    @Override
    public CodingPresentationSubscription observe(String sessionId) {
        requireOpen();
        var sub = decode(call("presentation/subscribe", Map.of("sessionId", sessionId), false, true), Subscribed.class);
        var scope = sub.snapshot().scope();
        CodingProtocolChecks.snapshot(sub.snapshot());
        if (!scope.sessionId().equals(sessionId)
                || !scope.clientId().equals(clientId)
                || !scope.hostIncarnation().equals(connection.hostIncarnation())
                || !scope.source().equals("native")) throw failure("VIEW_SCOPE_MISMATCH", false);
        var observation = new Observation(sub);
        observations.add(observation);
        if (closed.get()) {
            observation.close();
            throw failure("CLIENT_CLOSED", false);
        }
        observation.attach();
        return observation;
    }

    @Override
    public CodingPreviewSubscription preview(String sessionId, String runId) {
        requireOpen();
        if (!connection.capabilities().contains("preview-v1")) throw failure("PREVIEW_UNAVAILABLE", false);
        var result = call("preview/subscribe", Map.of("sessionId", sessionId, "runId", runId), false, true);
        var observation = new PreviewObservation(text(result, "subscriptionId"), sessionId, runId);
        previews.add(observation);
        if (closed.get()) {
            observation.close();
            throw failure("CLIENT_CLOSED", false);
        }
        observation.attach();
        return observation;
    }

    @Override
    public synchronized boolean renew() {
        requireOpen();
        try {
            call("client/renew", Map.of(), false, true);
            return false;
        } catch (CodingClientException unavailable) {
            if (!Set.of("CLIENT_CONTEXT_REQUIRED", "CLIENT_CONTEXT_EXPIRED").contains(unavailable.code()))
                throw unavailable;
            observations.forEach(Observation::close);
            previews.forEach(PreviewObservation::close);
            initialize(connection.projectId());
            return true;
        }
    }

    private JsonNode call(String method, Object params, boolean mutation, boolean context) {
        try {
            String id = Long.toString(ids.incrementAndGet());
            byte[] body =
                    json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            if (body.length > REQUEST_BYTES) throw failure("REQUEST_BUDGET_EXCEEDED", false);
            var builder = HttpRequest.newBuilder(endpoint.resolve("/v1/rpc"))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (context) builder.header("X-CA-Client-ID", clientId);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                Future<byte[]> bodyRead = executor.submit(() -> input.readNBytes(RESPONSE_BYTES + 1));
                byte[] bytes;
                try {
                    bytes = bodyRead.get(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException timedOut) {
                    throw failure("TRANSPORT_TIMEOUT", mutation);
                } catch (ExecutionException unavailable) {
                    throw failure("TRANSPORT_UNAVAILABLE", mutation);
                } finally {
                    bodyRead.cancel(true);
                }
                if (bytes.length > RESPONSE_BYTES) throw failure("RESPONSE_BUDGET_EXCEEDED", mutation);
                JsonNode envelope = json.readTree(bytes);
                if (envelope == null
                        || !envelope.isObject()
                        || !envelope.path("jsonrpc").asText().equals("2.0")
                        || !envelope.path("id").asText().equals(id)) throw failure("PROTOCOL_INCOMPATIBLE", mutation);
                if (envelope.has("error")) {
                    String reason = envelope.path("error")
                            .path("data")
                            .path("reasonCode")
                            .asText("REMOTE_REJECTED");
                    if (!reason.matches("[A-Z0-9_:.]{1,128}")) reason = "REMOTE_REJECTED";
                    throw failure(
                            reason,
                            mutation
                                    && !Set.of(
                                                    "CLIENT_CONTEXT_REQUIRED",
                                                    "REMOTE_METHOD_UNSUPPORTED",
                                                    "INVALID_PARAMS",
                                                    "HOST_BUSY",
                                                    "PROJECT_SCOPE_MISMATCH",
                                                    "RUN_SCOPE_MISMATCH",
                                                    "CLIENT_CONTEXT_EXPIRED")
                                            .contains(reason));
                }
                if (response.statusCode() != 200 || !envelope.has("result"))
                    throw failure("PROTOCOL_INCOMPATIBLE", mutation);
                return envelope.get("result");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("TRANSPORT_INTERRUPTED", mutation);
        } catch (IOException transport) {
            throw failure("TRANSPORT_UNAVAILABLE", mutation);
        }
    }

    private <T> T decode(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw failure("PROTOCOL_INCOMPATIBLE", false);
        }
    }

    private static String text(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > 512) throw failure("PROTOCOL_INCOMPATIBLE", false);
        return value.textValue();
    }

    private static CodingClientException failure(String code, boolean unknown) {
        return new CodingClientException(code, unknown);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        observations.forEach(Observation::close);
        observations.clear();
        previews.forEach(PreviewObservation::close);
        previews.clear();
        if (clientId != null && canCloseClient)
            try {
                byte[] body = json.writeValueAsBytes(
                        Map.of("jsonrpc", "2.0", "id", "close", "method", "client/close", "params", Map.of()));
                var request = HttpRequest.newBuilder(endpoint.resolve("/v1/rpc"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .header("X-CA-Client-ID", clientId)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                response.body().close();
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        http.shutdownNow();
        executor.shutdownNow();
    }

    private final class Observation implements CodingPresentationSubscription {
        private final Subscribed subscription;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean cleanupSent = new AtomicBoolean();
        private final ArrayDeque<Envelope> queue = new ArrayDeque<>();
        private int queuedBytes;
        private volatile InputStream input;
        private CompletableFuture<HttpResponse<InputStream>> attaching;
        private Snapshot confirmed;
        private final String observationClientId;

        Observation(Subscribed subscription) {
            this.subscription = subscription;
            confirmed = subscription.snapshot();
            observationClientId = clientId;
        }

        void attach() {
            if (stopped.get()) return;
            var request = HttpRequest.newBuilder(endpoint.resolve("/v1/events?subscriptionId="
                            + URLEncoder.encode(subscription.subscriptionId(), StandardCharsets.UTF_8)))
                    .header("X-CA-Client-ID", observationClientId)
                    .GET()
                    .build();
            attaching = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            if (stopped.get()) attaching.cancel(true);
            attaching
                    .thenAcceptAsync(
                            response -> {
                                if (stopped.get()) {
                                    try {
                                        response.body().close();
                                    } catch (IOException ignored) {
                                    }
                                    return;
                                }
                                if (response.statusCode() != 200
                                        || !response.headers()
                                                .firstValue("Content-Type")
                                                .orElse("")
                                                .startsWith("text/event-stream")) {
                                    try {
                                        response.body().close();
                                    } catch (IOException ignored) {
                                    }
                                    resync("OBSERVATION_UNAVAILABLE");
                                    return;
                                }
                                input = response.body();
                                try (var reader = new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                                .newDecoder()
                                                .onMalformedInput(CodingErrorAction.REPORT)
                                                .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                                    read(reader);
                                } catch (IOException | RuntimeException invalid) {
                                    if (!stopped.get()) resync("OBSERVATION_UNAVAILABLE");
                                } finally {
                                    if (!stopped.get()) resync("OBSERVATION_DISCONNECTED");
                                }
                            },
                            executor)
                    .exceptionally(failed -> {
                        if (!stopped.get()) resync("OBSERVATION_UNAVAILABLE");
                        return null;
                    });
        }

        private void read(Reader reader) throws IOException {
            SseReader.read(reader, "presentation", 65536, stopped::get, this::applyFrame);
        }

        private void applyFrame(String data) throws IOException {
            if (data.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IOException("SSE byte budget");
            var value = json.readTree(data);
            Envelope envelope;
            switch (value.path("kind").asText()) {
                case "update" -> {
                    var update = decode(value, Update.class);
                    Snapshot next = CodingPresentationReducer.apply(confirmed, update);
                    if (next == confirmed) return;
                    confirmed = next;
                    envelope = update;
                }
                case "resync" -> {
                    var resync = decode(value, Resync.class);
                    if (!resync.viewId().equals(confirmed.scope().viewId())) throw new IOException("SSE view mismatch");
                    envelope = resync;
                }
                default -> throw new IOException("SSE critical kind unsupported");
            }
            if (envelope instanceof Resync terminal) {
                resync(terminal.reason());
                return;
            }
            int bytes = json.writeValueAsBytes(envelope).length;
            boolean overflow;
            synchronized (queue) {
                if (stopped.get()) return;
                overflow = queuedBytes + bytes > 512 * 1024;
                if (!overflow) {
                    queue.add(envelope);
                    queuedBytes += bytes;
                    queue.notifyAll();
                }
            }
            if (overflow) resync("CLIENT_QUEUE_OVERFLOW");
        }

        private void resync(String reason) {
            synchronized (queue) {
                if (!stopped.compareAndSet(false, true)) return;
                queue.clear();
                queue.add(new Resync("resync", confirmed.scope().viewId(), reason));
                queuedBytes = 0;
                // Closed state and terminal envelope are published atomically to pollers.
                queue.notifyAll();
            }
            finishStop();
        }

        private boolean stop(boolean discard) {
            synchronized (queue) {
                if (!stopped.compareAndSet(false, true)) return false;
                if (discard) {
                    queue.clear();
                    queuedBytes = 0;
                }
                queue.notifyAll();
            }
            finishStop();
            return true;
        }

        private void finishStop() {
            observations.remove(this);
            var stream = input;
            if (stream != null)
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            if (attaching != null) attaching.cancel(true);
            closeRemote();
        }

        @Override
        public Snapshot initial() {
            return subscription.snapshot();
        }

        @Override
        public Envelope poll(Duration timeout) throws InterruptedException {
            if (timeout.isNegative()) throw new IllegalArgumentException("Negative observation timeout");
            long remaining = timeout.toNanos(), until = System.nanoTime() + remaining;
            synchronized (queue) {
                while (queue.isEmpty() && !stopped.get() && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(queue, remaining);
                    remaining = until - System.nanoTime();
                }
                Envelope value = queue.poll();
                if (value != null)
                    try {
                        queuedBytes = Math.max(0, queuedBytes - json.writeValueAsBytes(value).length);
                    } catch (IOException impossible) {
                        throw new IllegalStateException("Envelope encoding failed");
                    }
                return value;
            }
        }

        @Override
        public boolean closed() {
            return stopped.get();
        }

        @Override
        public void close() {
            stop(true);
            closeRemote();
        }

        private void closeRemote() {
            if (!cleanupSent.compareAndSet(false, true)) return;
            try {
                if (!HttpCodingClient.this.closed.get())
                    executor.execute(() -> {
                        try {
                            byte[] body = json.writeValueAsBytes(Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    "observation-close",
                                    "method",
                                    "presentation/close",
                                    "params",
                                    Map.of("subscriptionId", subscription.subscriptionId())));
                            var request = HttpRequest.newBuilder(endpoint.resolve("/v1/rpc"))
                                    .timeout(Duration.ofSeconds(2))
                                    .header("Content-Type", "application/json")
                                    .header("X-CA-Client-ID", observationClientId)
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                    .build();
                            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                            response.body().close();
                        } catch (IOException ignored) {
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            } catch (RejectedExecutionException closedExecutor) {
                /* Owner close already owns cleanup. */
            }
        }
    }

    private final class PreviewObservation implements CodingPreviewSubscription {
        private final String id, sessionId, runId, observationClientId = clientId;
        private final ArrayDeque<Preview> queue = new ArrayDeque<>();
        private volatile boolean stopped;
        private volatile String reason;
        private volatile InputStream input;
        private int bytes;
        private CompletableFuture<HttpResponse<InputStream>> attaching;

        PreviewObservation(String id, String sessionId, String runId) {
            this.id = id;
            this.sessionId = sessionId;
            this.runId = runId;
        }

        void attach() {
            if (stopped) return;
            var request = HttpRequest.newBuilder(endpoint.resolve(
                            "/v1/events?subscriptionId=" + URLEncoder.encode(id, StandardCharsets.UTF_8)))
                    .header("X-CA-Client-ID", observationClientId)
                    .GET()
                    .build();
            attaching = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            if (stopped) attaching.cancel(true);
            attaching
                    .thenAcceptAsync(
                            response -> {
                                if (stopped) {
                                    try {
                                        response.body().close();
                                    } catch (IOException ignored) {
                                    }
                                    return;
                                }
                                if (response.statusCode() != 200
                                        || !response.headers()
                                                .firstValue("Content-Type")
                                                .orElse("")
                                                .startsWith("text/event-stream")) {
                                    try {
                                        response.body().close();
                                    } catch (IOException ignored) {
                                    }
                                    fail("PREVIEW_UNAVAILABLE");
                                    return;
                                }
                                input = response.body();
                                try (var reader = new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                                .newDecoder()
                                                .onMalformedInput(CodingErrorAction.REPORT)
                                                .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                                    SseReader.read(reader, "tool-preview", 16384, () -> stopped, data -> {
                                        var frame = decode(json.readTree(data), Preview.class);
                                        if (!sessionId.equals(frame.sessionId())
                                                || !runId.equals(frame.runId())
                                                || frame.text().getBytes(StandardCharsets.UTF_8).length > 4096)
                                            throw new IOException("Invalid preview scope or budget");
                                        int length = json.writeValueAsBytes(frame).length;
                                        synchronized (queue) {
                                            if (stopped) return;
                                            if (bytes + length > 512 * 1024) {
                                                fail("PREVIEW_BUDGET_EXCEEDED");
                                                return;
                                            }
                                            queue.add(frame);
                                            bytes += length;
                                            queue.notifyAll();
                                        }
                                    });
                                } catch (IOException | RuntimeException failed) {
                                    if (!stopped) fail("PREVIEW_UNAVAILABLE");
                                } finally {
                                    if (!stopped) fail("PREVIEW_DISCONNECTED");
                                }
                            },
                            executor)
                    .exceptionally(failed -> {
                        if (!stopped) fail("PREVIEW_UNAVAILABLE");
                        return null;
                    });
        }

        private void fail(String code) {
            reason = code;
            close();
        }

        public Preview poll(Duration timeout) throws InterruptedException {
            if (timeout.isNegative()) throw new IllegalArgumentException("Negative preview timeout");
            long remaining = timeout.toNanos(), until = System.nanoTime() + remaining;
            synchronized (queue) {
                while (queue.isEmpty() && !stopped && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(queue, remaining);
                    remaining = until - System.nanoTime();
                }
                var value = queue.poll();
                if (value != null)
                    try {
                        bytes = Math.max(0, bytes - json.writeValueAsBytes(value).length);
                    } catch (IOException impossible) {
                        throw new IllegalStateException("Preview encoding failed");
                    }
                return value;
            }
        }

        public boolean closed() {
            return stopped;
        }

        public String unavailableReason() {
            return reason;
        }

        public void close() {
            synchronized (queue) {
                if (stopped) return;
                stopped = true;
                queue.clear();
                bytes = 0;
                queue.notifyAll();
            }
            previews.remove(this);
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            if (attaching != null) attaching.cancel(true);
            if (!HttpCodingClient.this.closed.get())
                try {
                    executor.execute(() -> {
                        try {
                            byte[] body = json.writeValueAsBytes(Map.of(
                                    "jsonrpc",
                                    "2.0",
                                    "id",
                                    "preview-close",
                                    "method",
                                    "preview/unsubscribe",
                                    "params",
                                    Map.of("subscriptionId", id)));
                            var request = HttpRequest.newBuilder(endpoint.resolve("/v1/rpc"))
                                    .timeout(Duration.ofSeconds(2))
                                    .header("Content-Type", "application/json")
                                    .header("X-CA-Client-ID", observationClientId)
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                    .build();
                            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                            response.body().close();
                        } catch (IOException ignored) {
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (RejectedExecutionException closedExecutor) {
                }
        }
    }
}
