package io.haifa.agent.mcp.tool;

import io.haifa.agent.mcp.config.McpServerId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record McpToolCatalogCandidateSnapshot(
        McpServerId serverId, long generation, Instant discoveredAt, List<McpToolImportCandidate> candidates) {
    public McpToolCatalogCandidateSnapshot {
        Objects.requireNonNull(serverId, "serverId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
