package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ManagedProcessRequest;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpServerDefinition;

@FunctionalInterface
public interface McpManagedProcessRequestTemplate {
    ManagedProcessRequest create(
            McpServerDefinition server, McpConnectionIdentity identity, ExecutionEnvironmentRef environment);
}
