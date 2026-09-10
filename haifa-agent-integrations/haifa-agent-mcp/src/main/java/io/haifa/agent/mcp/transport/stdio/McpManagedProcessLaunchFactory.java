package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpServerDefinition;
import java.util.Map;

@FunctionalInterface
public interface McpManagedProcessLaunchFactory {
    McpManagedProcessLaunch prepare(
            McpServerDefinition server, McpConnectionIdentity identity, Map<String, String> credentials);
}
