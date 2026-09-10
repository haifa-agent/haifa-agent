package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.mcp.internal.McpRequestContext;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import java.util.function.Supplier;

public final class McpStdioCredentialContext implements McpRequestContext {
    private static final String CONTEXT_KEY = "io.haifa.agent.mcp.stdio.credentials";
    private final ThreadLocal<RequestScope> current = new ThreadLocal<>();

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

    @Override
    public McpTransportContext snapshot() {
        RequestScope scope = current.get();
        return scope == null ? McpTransportContext.EMPTY : McpTransportContext.create(Map.of(CONTEXT_KEY, scope));
    }

    @SuppressWarnings("unchecked")
    RequestScope requestScope(McpTransportContext context) {
        Object value = context.get(CONTEXT_KEY);
        return value instanceof RequestScope scope ? scope : new RequestScope(Map.of(), ToolInvocationObserver.noop());
    }

    record RequestScope(
            Map<String, String> credentials,
            ToolInvocationObserver observer,
            java.util.concurrent.atomic.AtomicBoolean dispatchRecorded) {
        RequestScope(Map<String, String> credentials, ToolInvocationObserver observer) {
            this(Map.copyOf(credentials), observer, new java.util.concurrent.atomic.AtomicBoolean());
        }

        void dispatched() {
            if (dispatchRecorded.compareAndSet(false, true)) observer.dispatched();
        }
    }
}
