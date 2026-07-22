package io.haifa.agent.mcp.client;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.List;
import java.util.Map;

public interface McpClientFacade extends AutoCloseable {
    McpServerSnapshot initialize(List<CredentialLease> credentials);

    McpListToolsPage listTools(String cursor, List<CredentialLease> credentials);

    McpRemoteToolResult callTool(
            String name,
            Map<String, Object> arguments,
            List<CredentialLease> credentials,
            ToolInvocationObserver observer);

    McpConnectionState state();

    @Override
    void close();
}
