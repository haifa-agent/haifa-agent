package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.runtime.api.AgentRunRequest;
import java.util.List;

@FunctionalInterface
public interface CapabilityResolver {
    List<EffectiveCapability> resolve(AgentRunRequest request, ResolvedDefinition definition, ResolvedProfile profile);
}
