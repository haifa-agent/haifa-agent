package io.haifa.agent.mcp.tool;

import java.util.Optional;

public interface McpToolBindingStore {
    void put(McpToolBindingSnapshot snapshot);

    Optional<McpToolBindingSnapshot> find(String bindingReference);
}
