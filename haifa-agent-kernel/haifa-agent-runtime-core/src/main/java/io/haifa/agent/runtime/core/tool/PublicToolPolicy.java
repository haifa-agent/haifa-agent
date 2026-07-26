package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface PublicToolPolicy {
    PolicyDecision evaluate(AgentRun run, FrozenToolBinding binding, ToolRequest request);
}
