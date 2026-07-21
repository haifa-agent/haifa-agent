package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface CapabilityAuthorizer {
    boolean isAllowed(AgentRun run, ToolDefinition definition);
}
