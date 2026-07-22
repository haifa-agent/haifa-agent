package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface ToolPolicy {
    ToolPolicyDecision evaluate(AgentRun run, FrozenToolBinding binding, ToolRequest request);
}
