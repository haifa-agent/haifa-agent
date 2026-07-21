package io.haifa.agent.runtime.core.tool;

import java.util.Optional;

@FunctionalInterface
public interface ToolRegistry {
    Optional<ToolDefinition> find(String name, String version);
}
