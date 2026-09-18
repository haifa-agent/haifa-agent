package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import java.util.Objects;

public final class DefaultMcpClientFactory implements McpClientFactory {
    private final McpClientFactory http;
    private final McpClientFactory stdio;

    public DefaultMcpClientFactory(McpClientFactory http, McpClientFactory stdio) {
        this.http = Objects.requireNonNull(http, "http");
        this.stdio = Objects.requireNonNull(stdio, "stdio");
    }

    @Override
    public McpClientFacade create(McpServerDefinition server, McpConnectionIdentity identity) {
        if (server.transport() instanceof StreamableHttpDefinition) return http.create(server, identity);
        if (server.transport() instanceof StdioDefinition) return stdio.create(server, identity);
        throw new IllegalArgumentException("unsupported MCP transport definition");
    }
}
