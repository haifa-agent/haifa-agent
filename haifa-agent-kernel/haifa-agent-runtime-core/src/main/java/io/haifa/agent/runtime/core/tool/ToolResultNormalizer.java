package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolResult;

@FunctionalInterface
public interface ToolResultNormalizer {
    ToolResult normalize(ToolDefinition definition, ToolResult result);

    static ToolResultNormalizer identity() {
        return (definition, result) -> result;
    }
}
