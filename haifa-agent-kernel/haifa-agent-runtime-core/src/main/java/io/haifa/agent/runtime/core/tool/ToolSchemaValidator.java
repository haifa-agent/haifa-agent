package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolArguments;

@FunctionalInterface
public interface ToolSchemaValidator {
    void validate(ToolDefinition definition, ToolArguments arguments);
}
