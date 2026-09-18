package io.haifa.agent.mcp.config;

public record McpServerId(String value) implements Comparable<McpServerId> {
    public McpServerId {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("server id must be a lowercase stable identifier");
        }
    }

    @Override
    public int compareTo(McpServerId other) {
        return value.compareTo(other.value);
    }
}
