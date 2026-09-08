package io.haifa.agent.mcp.config;

import java.util.Set;

public record McpProtocolProfile(String targetVersion) {
    public static final String VERSION_2025_03_26 = "2025-03-26";
    public static final String VERSION_2025_06_18 = "2025-06-18";
    public static final String VERSION_2025_11_25 = "2025-11-25";
    public static final String VERSION_2026_07_28 = "2026-07-28";
    private static final Set<String> SUPPORTED_VERSIONS =
            Set.of(VERSION_2025_03_26, VERSION_2025_06_18, VERSION_2025_11_25, VERSION_2026_07_28);

    public static final McpProtocolProfile FIXED_2025_03_26 = new McpProtocolProfile(VERSION_2025_03_26);
    public static final McpProtocolProfile FIXED_2025_06_18 = new McpProtocolProfile(VERSION_2025_06_18);
    public static final McpProtocolProfile FIXED_2025_11_25 = new McpProtocolProfile(VERSION_2025_11_25);
    public static final McpProtocolProfile FIXED_2026_07_28 = new McpProtocolProfile(VERSION_2026_07_28);

    public McpProtocolProfile {
        if (!SUPPORTED_VERSIONS.contains(targetVersion)) {
            throw new IllegalArgumentException("unsupported Haifa MCP protocol version: " + targetVersion);
        }
    }

    public boolean isModern() {
        return VERSION_2026_07_28.equals(targetVersion);
    }
}
