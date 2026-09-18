package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.Map;

public interface McpClientFacade extends AutoCloseable {
    McpServerSnapshot initialize(Map<String, String> credentials);

    McpListToolsPage listTools(String cursor, Map<String, String> credentials);

    McpRemoteToolResult callTool(
            String name,
            Map<String, Object> arguments,
            Map<String, String> credentials,
            ToolInvocationObserver observer);

    McpConnectionState state();

    @Override
    void close();
}
