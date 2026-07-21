package io.haifa.agent.application.project.context;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Optional;

@FunctionalInterface
public interface TrustedProjectContextResolver {
    Optional<TrustedProjectContextBinding> resolve(AgentRunId runId);
}
