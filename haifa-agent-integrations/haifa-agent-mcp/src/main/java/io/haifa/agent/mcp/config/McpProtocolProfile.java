package io.haifa.agent.mcp.config;

public record McpProtocolProfile(String targetVersion) {
    public static final String VERSION_2025_11_25 = "2025-11-25";
    public static final McpProtocolProfile FIXED_2025_11_25 = new McpProtocolProfile(VERSION_2025_11_25);

    public McpProtocolProfile {
        if (!VERSION_2025_11_25.equals(targetVersion)) {
            throw new IllegalArgumentException("Haifa MCP only supports protocol 2025-11-25");
        }
    }
}
