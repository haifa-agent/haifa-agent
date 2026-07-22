package io.haifa.agent.mcp.internal;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import java.util.function.Supplier;

public interface McpRequestContext {
    <T> T withCredentials(List<CredentialLease> credentials, Supplier<T> action);

    McpTransportContext snapshot();

    default <T> T withInvocation(
            List<CredentialLease> credentials, ToolInvocationObserver observer, Supplier<T> action) {
        return withCredentials(credentials, action);
    }
}
