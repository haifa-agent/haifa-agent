package io.haifa.agent.mcp.transport.http;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Adds response budgets without replacing the official SDK Streamable HTTP transport. */
public final class BoundedHttpClientBuilder implements HttpClient.Builder {
    private final HttpClient.Builder delegate;
    private final long maxBodyBytes;
    private final long maxHeaderBytes;

    public BoundedHttpClientBuilder(HttpClient.Builder delegate, long maxBodyBytes, long maxHeaderBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxBodyBytes < 1 || maxHeaderBytes < 1) throw new IllegalArgumentException("HTTP budgets must be positive");
        this.maxBodyBytes = maxBodyBytes;
        this.maxHeaderBytes = maxHeaderBytes;
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
        return new BoundedHttpClient(delegate.build(), maxBodyBytes, maxHeaderBytes);
    }

    private static final class BoundedHttpClient extends HttpClient {
        private final HttpClient delegate;
        private final long maxBodyBytes;
        private final long maxHeaderBytes;

        private BoundedHttpClient(HttpClient delegate, long maxBodyBytes, long maxHeaderBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
            this.maxHeaderBytes = maxHeaderBytes;
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
            return delegate.sendAsync(request, bounded(handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return delegate.sendAsync(request, bounded(handler), pushPromiseHandler);
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
