package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpServerId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record McpCompatibilityReport(
        McpServerId serverId,
        String targetProtocolVersion,
        String negotiatedProtocolVersion,
        String clientSdkVersion,
        String serverCompatibilityBaseline,
        List<String> discoveredTools,
        boolean anonymousDiscoveryVerified,
        boolean authenticatedDiscoveryVerified,
        Instant verifiedAt) {
    public McpCompatibilityReport {
        Objects.requireNonNull(serverId, "serverId");
        targetProtocolVersion = text(targetProtocolVersion, "targetProtocolVersion");
        negotiatedProtocolVersion = text(negotiatedProtocolVersion, "negotiatedProtocolVersion");
        clientSdkVersion = text(clientSdkVersion, "clientSdkVersion");
        serverCompatibilityBaseline = text(serverCompatibilityBaseline, "serverCompatibilityBaseline");
        discoveredTools = List.copyOf(Objects.requireNonNull(discoveredTools, "discoveredTools"));
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
