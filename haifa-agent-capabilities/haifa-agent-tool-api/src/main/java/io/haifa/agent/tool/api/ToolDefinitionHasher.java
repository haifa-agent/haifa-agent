package io.haifa.agent.tool.api;

@FunctionalInterface
public interface ToolDefinitionHasher {
    ToolDefinitionHash hash(ToolDefinition definition);
}
