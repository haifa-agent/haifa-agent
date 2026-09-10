package io.haifa.agent.mcp.transport.http;

import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.internal.McpRequestContext;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class McpHttpCredentialContext implements McpRequestContext {
    private static final String CONTEXT_KEY = "io.haifa.agent.mcp.http.credentials";
    private final ThreadLocal<RequestScope> current = new ThreadLocal<>();
    private final List<McpCredentialInjection> injections;
    private final String allowedOrigin;

    public McpHttpCredentialContext(List<McpCredentialInjection> injections, String allowedOrigin) {
        this.injections = List.copyOf(Objects.requireNonNull(injections, "injections"));
        this.allowedOrigin = Objects.requireNonNull(allowedOrigin, "allowedOrigin");
    }

    @Override
    public <T> T withCredentials(Map<String, String> credentials, Supplier<T> action) {
        return withInvocation(credentials, ToolInvocationObserver.noop(), action);
    }

    @Override
    public <T> T withInvocation(
            Map<String, String> credentials, ToolInvocationObserver observer, Supplier<T> action) {
        if (current.get() != null) throw new IllegalStateException("nested MCP credential context is forbidden");
        current.set(new RequestScope(Map.copyOf(credentials), observer));
        try {
            return action.get();
        } finally {
            current.remove();
        }
    }

    public McpTransportContext snapshot() {
        RequestScope scope = current.get();
        return scope == null ? McpTransportContext.EMPTY : McpTransportContext.create(Map.of(CONTEXT_KEY, scope));
    }

    @SuppressWarnings("unchecked")
    public void customize(
            HttpRequest.Builder request, String method, URI uri, String body, McpTransportContext context) {
        if (!allowedOrigin.equals(origin(uri))) {
            throw new SecurityException("MCP HTTP redirect or request crossed the approved origin");
        }
        Object value = context.get(CONTEXT_KEY);
        RequestScope scope = value instanceof RequestScope requestScope
                ? requestScope
                : new RequestScope(Map.of(), ToolInvocationObserver.noop());
        Map<String, String> credentials = scope.credentials();
        for (McpCredentialInjection injection : injections) {
            String secret = credentials.get(injection.requirement().credentialId());
            if (secret == null || secret.isBlank()) {
                throw new SecurityException(
                        "MCP HTTP credential is missing: " + injection.requirement().credentialId());
            }
            request.header(injection.targetName(), injection.valuePrefix() + secret);
        }
        scope.dispatched();
    }

    private static String origin(URI uri) {
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
    }

    private record RequestScope(
            Map<String, String> credentials,
            ToolInvocationObserver observer,
            java.util.concurrent.atomic.AtomicBoolean dispatchRecorded) {
        private RequestScope(Map<String, String> credentials, ToolInvocationObserver observer) {
            this(
                    Map.copyOf(credentials),
                    Objects.requireNonNull(observer, "observer"),
                    new java.util.concurrent.atomic.AtomicBoolean());
        }

        private void dispatched() {
            if (dispatchRecorded.compareAndSet(false, true)) observer.dispatched();
        }
    }
}
