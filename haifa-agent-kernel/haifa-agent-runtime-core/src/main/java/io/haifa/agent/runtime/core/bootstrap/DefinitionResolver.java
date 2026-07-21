package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import java.util.Optional;

@FunctionalInterface
public interface DefinitionResolver {
    ResolvedDefinition resolve(AgentDefinitionId id, Optional<AgentDefinitionVersion> requestedVersion);
}
