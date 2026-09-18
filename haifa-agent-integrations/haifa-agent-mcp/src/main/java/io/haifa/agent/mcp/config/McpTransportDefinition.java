package io.haifa.agent.mcp.config;

public sealed interface McpTransportDefinition permits StreamableHttpDefinition, StdioDefinition {
    String identityReference();
}
