package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.runtime.api.AgentRunRequest;

@FunctionalInterface
public interface ConfigurationSnapshotFactory {
    RuntimeConfigurationSnapshot create(
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller);
}
