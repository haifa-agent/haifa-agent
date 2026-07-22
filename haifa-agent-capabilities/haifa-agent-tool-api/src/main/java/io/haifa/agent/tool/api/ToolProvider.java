package io.haifa.agent.tool.api;

import io.haifa.agent.core.tool.ToolResult;

public interface ToolProvider {
    ToolProviderId id();

    ToolResult invoke(ToolInvocationRequest request);
}
