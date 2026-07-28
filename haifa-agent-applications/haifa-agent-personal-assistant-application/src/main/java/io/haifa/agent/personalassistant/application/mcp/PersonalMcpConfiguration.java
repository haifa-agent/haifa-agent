package io.haifa.agent.personalassistant.application.mcp;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/** Pure-Java, explicit configuration for one trusted loopback MCP server. */
public record PersonalMcpConfiguration(
        URI endpoint, String serverId, String displayName, Set<String> allowedTools, String aliasNamespace) {
    public PersonalMcpConfiguration {
        Objects.requireNonNull(endpoint, "endpoint");
        serverId = text(serverId, "serverId");
        displayName = text(displayName, "displayName");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        if (allowedTools.isEmpty() || allowedTools.size() > 16) {
            throw new IllegalArgumentException("allowedTools must contain 1 to 16 entries");
        }
        if (allowedTools.stream().anyMatch(tool -> !tool.matches("[a-z][a-z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("allowedTools contains an invalid MCP Tool name");
        }
        if (aliasNamespace == null || !aliasNamespace.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("aliasNamespace is invalid");
        }
    }

    private static String text(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
        }
        return normalized;
    }
}
