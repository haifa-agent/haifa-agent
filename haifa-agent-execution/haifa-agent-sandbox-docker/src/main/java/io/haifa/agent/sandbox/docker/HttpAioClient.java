package io.haifa.agent.sandbox.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Narrow HTTP client for the fixed AIO v1.11.0 subset. */
final class HttpAioClient implements AioClient {
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    HttpAioClient(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean awaitReady(URI baseUri, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = Math.max(1, deadline - System.nanoTime());
            Duration requestTimeout =
                    Duration.ofNanos(Math.min(remaining, Duration.ofMillis(750).toNanos()));
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/sandbox"))
                        .timeout(requestTimeout)
                        .GET()
                        .build();
                CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, boundedBodyHandler());
                HttpResponse<byte[]> response =
                        future.get(Math.max(1, requestTimeout.toMillis()), TimeUnit.MILLISECONDS);
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    if (!root.has("success")
                            || !root.get("success").isBoolean()
                            || root.get("success").booleanValue()) {
                        return true;
                    }
                }
            } catch (TimeoutException | ExecutionException | IOException | AioProtocolException ignored) {
                // Probe again only until the fixed deadline; no command has been dispatched.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public ExecResult exec(URI baseUri, ExecRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", request.command());
        if (request.execDirectory() != null) body.put("exec_dir", request.execDirectory());
        if (!request.environment().isEmpty()) body.put("env", request.environment());
        body.put("async_mode", false);
        body.put("timeout", seconds(request.softTimeout()));
        body.put("hard_timeout", seconds(request.hardTimeout()));
        body.put("max_output_length", Math.max(1, request.maxOutputCharacters()));
        Duration effectiveTimeout = request.hardTimeout().compareTo(request.softTimeout()) > 0
                ? request.hardTimeout()
                : request.softTimeout();
        Duration transportTimeout = effectiveTimeout.plusSeconds(2);
        JsonNode root = post(baseUri.resolve("/v1/bash/exec"), body, transportTimeout);
        requireSuccess(root, "AIO_EXEC_FAILED");
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new AioProtocolException("AIO_EXEC_PROTOCOL_ERROR", "AIO exec response has no data");
        }
        String status = text(data, "status");
        if (status == null || status.isBlank()) {
            throw new AioProtocolException("AIO_EXEC_PROTOCOL_ERROR", "AIO exec response has no status");
        }
        Integer exitCode = data.hasNonNull("exit_code") ? data.get("exit_code").intValue() : null;
        String stdout = text(data, "stdout");
        String stderr = text(data, "stderr");
        int max = Math.max(1, request.maxOutputCharacters());
        return new ExecResult(
                text(data, "session_id"),
                text(data, "command_id"),
                status,
                exitCode,
                stdout == null ? new byte[0] : stdout.getBytes(StandardCharsets.UTF_8),
                stderr == null ? new byte[0] : stderr.getBytes(StandardCharsets.UTF_8),
                stdout != null && stdout.length() >= max,
                stderr != null && stderr.length() >= max);
    }

    @Override
    public void kill(URI baseUri, String sessionId, Duration timeout) {
        if (!validSession(sessionId)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("signal", "SIGKILL");
        try {
            post(baseUri.resolve("/v1/bash/kill"), body, timeout);
        } catch (RuntimeException ignored) {
            // Container deletion is the authoritative stop; the kill probe is best effort.
        }
    }

    @Override
    public void closeSession(URI baseUri, String sessionId, Duration timeout) {
        if (!validSession(sessionId)) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/bash/sessions/" + sessionId + "/close"))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException ignored) {
            // The container removal is the authoritative cleanup.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode post(URI uri, Map<String, Object> body, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> responseFuture = null;
        try {
            byte[] payload = mapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            responseFuture = client.sendAsync(request, boundedBodyHandler());
            HttpResponse<byte[]> response = responseFuture.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new AioProtocolException("AIO_HTTP_" + response.statusCode(), "AIO request was rejected");
            }
            return mapper.readTree(response.body());
        } catch (AioProtocolException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            if (responseFuture != null) responseFuture.cancel(true);
            throw new AioProtocolException("AIO_TIMED_OUT", "AIO request timed out", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            if (cause instanceof AioProtocolException aioException) {
                throw aioException;
            }
            if (cause instanceof HttpTimeoutException timeoutException) {
                throw new AioProtocolException("AIO_TIMED_OUT", "AIO request timed out", timeoutException);
            }
            if (cause instanceof IOException ioException) {
                throw new AioProtocolException("AIO_TRANSPORT_ERROR", "AIO request failed", ioException);
            }
            throw new AioProtocolException("AIO_TRANSPORT_ERROR", "AIO request failed", cause);
        } catch (IOException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AioProtocolException aioException) {
                throw aioException;
            }
            if (cause instanceof HttpTimeoutException timeoutException) {
                throw new AioProtocolException("AIO_TIMED_OUT", "AIO request timed out", timeoutException);
            }
            throw new AioProtocolException("AIO_TRANSPORT_ERROR", "AIO request failed", exception);
        } catch (InterruptedException exception) {
            if (responseFuture != null) responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new AioProtocolException("AIO_INTERRUPTED", "AIO request was interrupted", exception);
        }
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler() {
        return responseInfo -> {
            if (responseInfo.statusCode() != 200) {
                return HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.discarding(), ignored -> new byte[0]);
            }
            return new BoundedByteArraySubscriber(MAX_RESPONSE_BYTES);
        };
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int count = 0;

        BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return future;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = Objects.requireNonNull(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> item) {
            if (future.isDone()) {
                return;
            }
            for (ByteBuffer buf : item) {
                int remaining = buf.remaining();
                if (remaining > 0) {
                    if ((long) count + remaining > maxBytes) {
                        subscription.cancel();
                        future.completeExceptionally(new AioProtocolException(
                                "AIO_RESPONSE_TOO_LARGE", "AIO response exceeded maximum size limit"));
                        return;
                    }
                    count += remaining;
                    byte[] bytes = new byte[remaining];
                    buf.get(bytes);
                    buffer.write(bytes, 0, remaining);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        @Override
        public synchronized void onComplete() {
            if (!future.isDone()) {
                future.complete(buffer.toByteArray());
            }
        }
    }

    private static void requireSuccess(JsonNode root, String code) {
        if (root == null || root.isNull()) {
            throw new AioProtocolException(code, "AIO response is empty");
        }
        if (root.has("success")
                && root.get("success").isBoolean()
                && !root.get("success").booleanValue()) {
            throw new AioProtocolException(code, "AIO reported an unsuccessful operation");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long seconds(Duration value) {
        return Math.max(1, (value.toMillis() + 999) / 1000);
    }

    private static boolean validSession(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }
}
