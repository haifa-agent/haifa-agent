package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.ToolRequest;

@FunctionalInterface
public interface ApprovalGateway {
    boolean approve(AgentRun run, ToolRequest request);
}
