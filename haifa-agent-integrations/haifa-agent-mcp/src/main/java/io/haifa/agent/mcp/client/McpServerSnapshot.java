package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpServerId;
import java.util.Objects;

public record McpServerSnapshot(
        McpServerId serverId,
        String serverBindingReference,
        String serverBindingDigest,
        String targetProtocolVersion,
        String negotiatedProtocolVersion,
        String serverName,
        String serverVersion,
        boolean toolsCapability,
        boolean toolsListChanged,
        boolean resourcesCapability,
        boolean promptsCapability) {
    public McpServerSnapshot {
        Objects.requireNonNull(serverId, "serverId");
        serverBindingReference = text(serverBindingReference, "serverBindingReference");
        serverBindingDigest = text(serverBindingDigest, "serverBindingDigest");
        targetProtocolVersion = text(targetProtocolVersion, "targetProtocolVersion");
        negotiatedProtocolVersion = text(negotiatedProtocolVersion, "negotiatedProtocolVersion");
        serverName = text(serverName, "serverName");
        serverVersion = text(serverVersion, "serverVersion");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
