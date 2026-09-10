package io.haifa.agent.mcp.internal;

import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
import java.util.function.Supplier;

public interface McpRequestContext {
    <T> T withCredentials(Map<String, String> credentials, Supplier<T> action);

    McpTransportContext snapshot();

    default <T> T withInvocation(Map<String, String> credentials, ToolInvocationObserver observer, Supplier<T> action) {
        return withCredentials(credentials, action);
    }
}
