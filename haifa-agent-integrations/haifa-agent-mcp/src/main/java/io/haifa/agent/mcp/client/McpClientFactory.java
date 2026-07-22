package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpServerDefinition;

@FunctionalInterface
public interface McpClientFactory {
    McpClientFacade create(McpServerDefinition server, McpConnectionIdentity identity);
}
