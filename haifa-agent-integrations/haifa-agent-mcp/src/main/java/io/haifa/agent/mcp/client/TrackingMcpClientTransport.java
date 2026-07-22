package io.haifa.agent.mcp.client;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;

final class TrackingMcpClientTransport implements McpClientTransport {
    private final McpClientTransport delegate;
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

    TrackingMcpClientTransport(McpClientTransport delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    Optional<Throwable> consumeFailure() {
        return Optional.ofNullable(lastFailure.getAndSet(null));
    }

    void clearFailure() {
        lastFailure.set(null);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(handler);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> exceptionHandler) {
        delegate.setExceptionHandler(error -> {
            lastFailure.set(error);
            exceptionHandler.accept(error);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
