package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.application.project.product.ProjectProductException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** One loopback JSON-RPC command channel plus SSE stream opener shared by the remote clients. */
final class RemoteRpcTransport {
    static final int MAX_REQUEST_BYTES = 256 * 1024;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration SSE_ATTACH_TIMEOUT = Duration.ofSeconds(15);
    static final Duration BEST_EFFORT_TIMEOUT = Duration.ofSeconds(2);

    private final URI baseUri;
    private final URI rpcUri;
    private final URI eventsUri;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final HttpClient http;
    private final AtomicLong requestIdentifiers = new AtomicLong();
    private volatile String clientId;
    private volatile boolean closed;

    RemoteRpcTransport(URI baseUri, ObjectMapper json, ExecutorService executor) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.rpcUri = baseUri.resolve("/v1/rpc");
        this.eventsUri = baseUri.resolve("/v1/events");
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    URI baseUri() {
        return baseUri;
    }

    ObjectMapper json() {
        return json;
    }

    ExecutorService executor() {
        return executor;
    }

    void clientId(String value) {
        this.clientId = value;
    }

    String clientId() {
        return clientId;
    }

    void close() {
        closed = true;
    }

    JsonNode call(String method, Map<String, Object> params, boolean mutation) {
        return call(method, params, mutation, REQUEST_TIMEOUT);
    }

    void callBestEffort(String method, Map<String, Object> params) {
        if (closed) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    call(method, params, false, BEST_EFFORT_TIMEOUT);
                } catch (RuntimeException ignored) {
                    // Observation cleanup is best effort and never changes Run state.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The adapter is already closing; Host attachment cleanup retires the observation.
        }
    }

    private JsonNode call(String method, Map<String, Object> params, boolean mutation, Duration timeout) {
        if (closed) {
            throw unavailable();
        }
        String requestId = "r" + requestIdentifiers.incrementAndGet();
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        request.set("params", json.valueToTree(params == null ? Map.of() : params));
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(request);
        } catch (IOException exception) {
            throw protocolIncompatible();
        }
        if (payload.length > MAX_REQUEST_BYTES) {
            throw new ProjectProductException(
                    "REMOTE_PAYLOAD_TOO_LARGE", "Remote request exceeds the bounded payload size");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(rpcUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        String activeClientId = clientId;
        if (activeClientId != null) {
            builder.header("X-CA-Client-ID", activeClientId);
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw transportFailure(mutation);
        } catch (IOException | IllegalArgumentException exception) {
            throw transportFailure(mutation);
        }
        boolean jsonContentType = hasContentType(response, "application/json");
        byte[] body;
        try {
            body = readBody(response.body(), mutation, timeout);
        } catch (IOException exception) {
            throw transportFailure(mutation);
        }
        if (!jsonContentType) {
            throw protocolIncompatible();
        }
        JsonNode envelope = parse(body);
        if (envelope == null || !envelope.isObject()) {
            throw protocolIncompatible();
        }
        JsonNode version = envelope.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.textValue())) {
            throw protocolIncompatible();
        }
        JsonNode responseId = envelope.get("id");
        if (responseId == null || !responseId.isTextual() || !requestId.equals(responseId.textValue())) {
            throw protocolIncompatible();
        }
        JsonNode error = envelope.get("error");
        if (error != null && !error.isNull()) {
            throw hostError(error);
        }
        if (!envelope.has("result")) {
            throw protocolIncompatible();
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw transportFailure(mutation);
        }
        return envelope.get("result");
    }

    HttpResponse<InputStream> openEventStream(String subscriptionId) {
        if (closed) {
            throw unavailable();
        }
        String activeClientId = clientId;
        if (activeClientId == null) {
            throw unavailable();
        }
        String query = "?subscriptionId=" + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(eventsUri + query))
                .timeout(SSE_ATTACH_TIMEOUT)
                .header("Accept", "text/event-stream")
                .header("X-CA-Client-ID", activeClientId)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable();
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            try {
                readBody(response.body(), false, BEST_EFFORT_TIMEOUT);
            } catch (IOException | RuntimeException ignored) {
                // The status already identifies a rejected observation.
            }
            throw unavailable();
        }
        if (!hasContentType(response, "text/event-stream")) {
            closeQuietly(response.body());
            throw protocolIncompatible();
        }
        return response;
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        try (stream) {
            byte[] body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new ProjectProductException(
                        "REMOTE_PAYLOAD_TOO_LARGE", "Remote response exceeds the bounded payload size");
            }
            return body;
        }
    }

    private byte[] readBody(InputStream stream, boolean mutation, Duration timeout) throws IOException {
        Future<byte[]> task;
        try {
            task = executor.submit(() -> readBounded(stream));
        } catch (RejectedExecutionException exception) {
            closeQuietly(stream);
            throw transportFailure(mutation);
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            closeQuietly(stream);
            throw transportFailure(mutation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            closeQuietly(stream);
            throw transportFailure(mutation);
        } catch (CancellationException exception) {
            closeQuietly(stream);
            throw transportFailure(mutation);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProjectProductException product) {
                throw product;
            }
            throw transportFailure(mutation);
        }
    }

    private static boolean hasContentType(HttpResponse<?> response, String token) {
        return response.headers()
                .firstValue("Content-Type")
                .map(value -> {
                    int separator = value.indexOf(';');
                    String mime = (separator < 0 ? value : value.substring(0, separator))
                            .trim()
                            .toLowerCase(Locale.ROOT);
                    return mime.equals(token);
                })
                .orElse(false);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Body cleanup is best effort.
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException exception) {
            throw protocolIncompatible();
        }
    }

    private ProjectProductException hostError(JsonNode error) {
        if (!error.isObject()) {
            return protocolIncompatible();
        }
        JsonNode data = error.get("data");
        JsonNode reason = data == null || !data.isObject() ? null : data.get("reasonCode");
        String code = reason != null && reason.isTextual() ? reason.textValue().strip() : "";
        if (!code.matches("[A-Z][A-Z0-9_.:-]{0,95}")) {
            code = "REMOTE_HOST_ERROR";
        }
        return new ProjectProductException(code, "Remote Coding Host rejected the request");
    }

    static ProjectProductException protocolIncompatible() {
        return new ProjectProductException(
                "REMOTE_PROTOCOL_INCOMPATIBLE", "Remote Coding Host response is incompatible with protocol 1");
    }

    static ProjectProductException unavailable() {
        return new ProjectProductException("REMOTE_TRANSPORT_UNAVAILABLE", "Remote Coding Host is unavailable");
    }

    static ProjectProductException transportFailure(boolean mutation) {
        return mutation
                ? new ProjectProductException(
                        "REMOTE_OUTCOME_UNKNOWN",
                        "Remote command outcome is unknown; reconcile before retrying with the same key")
                : unavailable();
    }

    static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                values.put(String.valueOf(keyValues[index]), value);
            }
        }
        return values;
    }
}
