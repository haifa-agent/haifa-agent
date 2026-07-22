package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface CapabilityAuthorizer {
    boolean isAllowed(AgentRun run, FrozenToolBinding binding);
}
