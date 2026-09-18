package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface ToolPolicyRequestAdapter {
    PolicyRequest adapt(AgentRun run, FrozenToolBinding binding, ToolRequest request);
}
