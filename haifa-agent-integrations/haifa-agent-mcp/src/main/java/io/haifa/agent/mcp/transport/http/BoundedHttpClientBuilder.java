package io.haifa.agent.mcp.transport.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Adds response budgets and bounded GET stream recovery without replacing the official SDK protocol transport. */
public final class BoundedHttpClientBuilder implements HttpClient.Builder {
    private final HttpClient.Builder delegate;
    private final long maxBodyBytes;
    private final long maxHeaderBytes;
    private final int maxReconnectAttempts;
    private final Duration reconnectBaseDelay;
    private final Set<String> nonReplayableHeaders;

    public BoundedHttpClientBuilder(HttpClient.Builder delegate, long maxBodyBytes, long maxHeaderBytes) {
        this(delegate, maxBodyBytes, maxHeaderBytes, 0, Duration.ofMillis(25), Set.of());
    }

    public BoundedHttpClientBuilder(
            HttpClient.Builder delegate,
            long maxBodyBytes,
            long maxHeaderBytes,
            int maxReconnectAttempts,
            Duration reconnectBaseDelay,
            Set<String> nonReplayableHeaders) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxBodyBytes < 1 || maxHeaderBytes < 1) throw new IllegalArgumentException("HTTP budgets must be positive");
        if (maxReconnectAttempts < 0) throw new IllegalArgumentException("maxReconnectAttempts must not be negative");
        if (Objects.requireNonNull(reconnectBaseDelay, "reconnectBaseDelay").isNegative()) {
            throw new IllegalArgumentException("reconnectBaseDelay must not be negative");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectBaseDelay = reconnectBaseDelay;
        this.nonReplayableHeaders = normalizeHeaders(nonReplayableHeaders);
    }

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
        delegate.cookieHandler(cookieHandler);
        return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration duration) {
        delegate.connectTimeout(duration);
        return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext sslContext) {
        delegate.sslContext(sslContext);
        return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
        delegate.sslParameters(sslParameters);
        return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
        delegate.executor(executor);
        return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
        delegate.followRedirects(policy);
        return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
        delegate.version(version);
        return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
        delegate.priority(priority);
        return this;
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector proxySelector) {
        delegate.proxy(proxySelector);
        return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
        delegate.authenticator(authenticator);
        return this;
    }

    @Override
    public HttpClient build() {
        return new BoundedHttpClient(
                delegate.build(),
                maxBodyBytes,
                maxHeaderBytes,
                maxReconnectAttempts,
                reconnectBaseDelay,
                nonReplayableHeaders);
    }

    private static Set<String> normalizeHeaders(Set<String> headers) {
        Objects.requireNonNull(headers, "nonReplayableHeaders");
        Set<String> normalized = new HashSet<>();
        headers.forEach(header ->
                normalized.add(Objects.requireNonNull(header, "header").toLowerCase(Locale.ROOT)));
        return Set.copyOf(normalized);
    }

    private static final class BoundedHttpClient extends HttpClient {
        private final HttpClient delegate;
        private final long maxBodyBytes;
        private final long maxHeaderBytes;
        private final int maxReconnectAttempts;
        private final Duration reconnectBaseDelay;
        private final Set<String> nonReplayableHeaders;
        private final Set<ResumeControl<?>> activeResumes = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private BoundedHttpClient(
                HttpClient delegate,
                long maxBodyBytes,
                long maxHeaderBytes,
                int maxReconnectAttempts,
                Duration reconnectBaseDelay,
                Set<String> nonReplayableHeaders) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
            this.maxHeaderBytes = maxHeaderBytes;
            this.maxReconnectAttempts = maxReconnectAttempts;
            this.reconnectBaseDelay = reconnectBaseDelay;
            this.nonReplayableHeaders = nonReplayableHeaders;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            return delegate.send(request, bounded(handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            if ("DELETE".equals(request.method())) stopMatchingResumes(request);
            if (isResumableGet(request)) return sendResumableGet(request, handler);
            return delegate.sendAsync(request, bounded(handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return delegate.sendAsync(request, bounded(handler), pushPromiseHandler);
        }

        private boolean isResumableGet(HttpRequest request) {
            if (!"GET".equals(request.method()) || maxReconnectAttempts == 0) return false;
            return nonReplayableHeaders.stream()
                    .noneMatch(header -> request.headers().firstValue(header).isPresent());
        }

        private <T> CompletableFuture<HttpResponse<T>> sendResumableGet(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            var control = new ResumeControl<T>(request);
            activeResumes.add(control);
            attemptGet(control, handler, 0);
            control.result.whenComplete((ignored, error) -> activeResumes.remove(control));
            return control.result;
        }

        private <T> void attemptGet(ResumeControl<T> control, HttpResponse.BodyHandler<T> handler, int retry) {
            if (control.stopped.get()) return;
            HttpRequest request = retry == 0
                    ? control.initialRequest
                    : resumeRequest(control.initialRequest, control.lastEventId.get());
            CompletableFuture<HttpResponse<T>> attempt =
                    delegate.sendAsync(request, bounded(tracking(handler, control.lastEventId)));
            control.currentAttempt.set(attempt);
            attempt.whenComplete((response, error) -> {
                if (control.stopped.get()) return;
                boolean reconnect = error != null || isCompletedEventStream(response);
                if (!reconnect || retry >= maxReconnectAttempts) {
                    if (error == null) control.result.complete(response);
                    else control.result.completeExceptionally(error);
                    return;
                }
                long delayMillis = reconnectDelayMillis(retry);
                CompletableFuture.runAsync(
                        () -> attemptGet(control, handler, retry + 1),
                        CompletableFuture.delayedExecutor(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS));
            });
        }

        private long reconnectDelayMillis(int retry) {
            long base = reconnectBaseDelay.toMillis();
            long exponential = base == 0 ? 0 : Math.min(1_000L, base << Math.min(retry, 5));
            long jitter = exponential == 0 ? 0 : ThreadLocalRandom.current().nextLong(exponential + 1);
            return exponential + jitter;
        }

        private static boolean isCompletedEventStream(HttpResponse<?> response) {
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) return false;
            return response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.toLowerCase(Locale.ROOT).contains("text/event-stream"))
                    .orElse(false);
        }

        private static HttpRequest resumeRequest(HttpRequest original, String lastEventId) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(original.uri())
                    .expectContinue(original.expectContinue())
                    .method("GET", HttpRequest.BodyPublishers.noBody());
            original.timeout().ifPresent(builder::timeout);
            original.version().ifPresent(builder::version);
            original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            if (lastEventId != null && !lastEventId.isBlank()) builder.setHeader("Last-Event-ID", lastEventId);
            return builder.build();
        }

        private void stopMatchingResumes(HttpRequest delete) {
            String deleteSession = delete.headers().firstValue("Mcp-Session-Id").orElse(null);
            activeResumes.stream()
                    .filter(control -> control.initialRequest.uri().equals(delete.uri()))
                    .filter(control -> Objects.equals(
                            deleteSession,
                            control.initialRequest
                                    .headers()
                                    .firstValue("Mcp-Session-Id")
                                    .orElse(null)))
                    .forEach(ResumeControl::stop);
        }

        private <T> HttpResponse.BodyHandler<T> tracking(
                HttpResponse.BodyHandler<T> handler, AtomicReference<String> lastEventId) {
            return info -> new EventIdTrackingSubscriber<>(handler.apply(info), lastEventId);
        }

        private <T> HttpResponse.BodyHandler<T> bounded(HttpResponse.BodyHandler<T> handler) {
            return info -> {
                long headerBytes = info.headers().map().entrySet().stream()
                        .mapToLong(entry -> utf8(entry.getKey())
                                + entry.getValue().stream()
                                        .mapToLong(BoundedHttpClient::utf8)
                                        .sum()
                                + (2L * entry.getValue().size()))
                        .sum();
                if (headerBytes > maxHeaderBytes) {
                    throw new McpHttpResponseLimitException(
                            "MCP_HTTP_RESPONSE_HEADERS_TOO_LARGE",
                            "MCP HTTP response headers exceeded configured budget");
                }
                long contentLength =
                        info.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (contentLength > maxBodyBytes) {
                    throw new McpHttpResponseLimitException(
                            "MCP_HTTP_RESPONSE_BODY_TOO_LARGE", "MCP HTTP response body exceeded configured budget");
                }
                return new BoundedBodySubscriber<>(handler.apply(info), maxBodyBytes);
            };
        }

        private static long utf8(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    private static final class ResumeControl<T> {
        private final HttpRequest initialRequest;
        private final AtomicReference<String> lastEventId = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<?>> currentAttempt = new AtomicReference<>();
        private final CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();

        private ResumeControl(HttpRequest initialRequest) {
            this.initialRequest = initialRequest;
        }

        private void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            CompletableFuture<?> current = currentAttempt.get();
            if (current != null) current.cancel(true);
            result.cancel(true);
        }
    }

    private static final class EventIdTrackingSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final AtomicReference<String> lastEventId;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private EventIdTrackingSubscriber(
                HttpResponse.BodySubscriber<T> delegate, AtomicReference<String> lastEventId) {
            this.delegate = delegate;
            this.lastEventId = lastEventId;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            for (ByteBuffer source : item) {
                ByteBuffer copy = source.asReadOnlyBuffer();
                while (copy.hasRemaining()) accept(copy.get());
            }
            delegate.onNext(item);
        }

        private void accept(byte value) {
            if (value == '\n') {
                recordLine();
                line.reset();
            } else {
                line.write(value);
            }
        }

        private void recordLine() {
            String value = line.toString(StandardCharsets.UTF_8);
            if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
            if (!value.startsWith("id:")) return;
            String id = value.substring(3);
            if (id.startsWith(" ")) id = id.substring(1);
            if (!id.contains("\0")) lastEventId.set(id);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (line.size() > 0) recordLine();
            delegate.onComplete();
        }
    }

    private static final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final long maxBytes;
        private long received;
        private Flow.Subscription subscription;
        private boolean terminated;

        private BoundedBodySubscriber(HttpResponse.BodySubscriber<T> delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (terminated) return;
            long chunk = item.stream().mapToLong(ByteBuffer::remaining).sum();
            if (chunk > maxBytes - received) {
                terminated = true;
                subscription.cancel();
                delegate.onError(new McpHttpResponseLimitException(
                        "MCP_HTTP_RESPONSE_BODY_TOO_LARGE", "MCP HTTP response body exceeded configured budget"));
                return;
            }
            received += chunk;
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (terminated) return;
            terminated = true;
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (terminated) return;
            terminated = true;
            delegate.onComplete();
        }
    }
}
