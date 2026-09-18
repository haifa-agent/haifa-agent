package io.haifa.agent.mcp.tool;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMcpToolBindingStore implements McpToolBindingStore {
    private final ConcurrentHashMap<String, McpToolBindingSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void put(McpToolBindingSnapshot snapshot) {
        McpToolBindingSnapshot previous = snapshots.putIfAbsent(snapshot.bindingReference(), snapshot);
        if (previous != null && !previous.equals(snapshot)) {
            throw new IllegalStateException("MCP binding reference collision");
        }
    }

    @Override
    public Optional<McpToolBindingSnapshot> find(String bindingReference) {
        return Optional.ofNullable(snapshots.get(bindingReference));
    }
}
