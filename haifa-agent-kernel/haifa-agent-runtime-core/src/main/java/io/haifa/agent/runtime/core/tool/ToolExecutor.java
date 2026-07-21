package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.decision.ToolRequest;

@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(AgentRun run, ToolDefinition definition, ToolRequest request);
}
