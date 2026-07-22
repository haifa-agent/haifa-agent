package io.haifa.agent.mcp.transport.http;

import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.internal.McpRequestContext;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
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

    public <T> T withCredentials(List<CredentialLease> credentials, Supplier<T> action) {
        return withInvocation(credentials, ToolInvocationObserver.noop(), action);
    }

    @Override
    public <T> T withInvocation(
            List<CredentialLease> credentials, ToolInvocationObserver observer, Supplier<T> action) {
        if (current.get() != null) throw new IllegalStateException("nested MCP credential context is forbidden");
        current.set(new RequestScope(List.copyOf(credentials), observer));
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
                : new RequestScope(List.of(), ToolInvocationObserver.noop());
        List<CredentialLease> credentials = scope.credentials();
        if (credentials.size() != injections.size()) {
            if (!injections.isEmpty()) throw new SecurityException("MCP HTTP credential lease set is incomplete");
            return;
        }
        for (int index = 0; index < injections.size(); index++) {
            McpCredentialInjection injection = injections.get(index);
            if (injection.requirement().exposureMode() != CredentialExposureMode.HTTP_HEADER) {
                throw new SecurityException("non-header credential cannot be injected into MCP HTTP");
            }
            CredentialLease lease = credentials.get(index);
            lease.use(secret -> {
                request.header(
                        injection.targetName(), injection.valuePrefix() + new String(secret, StandardCharsets.UTF_8));
                return null;
            });
        }
        scope.dispatched();
    }

    private static String origin(URI uri) {
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
    }

    private record RequestScope(
            List<CredentialLease> credentials,
            ToolInvocationObserver observer,
            java.util.concurrent.atomic.AtomicBoolean dispatchRecorded) {
        private RequestScope(List<CredentialLease> credentials, ToolInvocationObserver observer) {
            this(
                    List.copyOf(credentials),
                    Objects.requireNonNull(observer, "observer"),
                    new java.util.concurrent.atomic.AtomicBoolean());
        }

        private void dispatched() {
            if (dispatchRecorded.compareAndSet(false, true)) observer.dispatched();
        }
    }
}
