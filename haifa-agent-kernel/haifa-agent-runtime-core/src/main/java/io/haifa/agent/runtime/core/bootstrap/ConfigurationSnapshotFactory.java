package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.api.AgentRunRequest;
import java.util.List;

@FunctionalInterface
public interface ConfigurationSnapshotFactory {
    RuntimeConfigurationSnapshot create(
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller,
            List<EffectiveCapability> capabilities);

    /** Child-specific freezing may inherit facts from the authoritative Parent Run. */
    default RuntimeConfigurationSnapshot createChild(
            AgentRun parent,
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller,
            List<EffectiveCapability> capabilities) {
        return create(request, definition, profile, caller, capabilities);
    }
}
