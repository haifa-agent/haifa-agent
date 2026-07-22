package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface ToolResultNormalizer {
    ToolResult normalize(FrozenToolBinding binding, ToolResult result);

    static ToolResultNormalizer identity() {
        return (definition, result) -> result;
    }
}
