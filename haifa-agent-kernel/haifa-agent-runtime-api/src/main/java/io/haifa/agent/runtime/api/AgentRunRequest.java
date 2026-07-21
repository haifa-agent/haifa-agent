package io.haifa.agent.runtime.api;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

/** Stable identity-and-version request to start a reproducible run. */
public record AgentRunRequest(
        AgentDefinitionId agentDefinitionId,
        AgentDefinitionVersion agentDefinitionVersion,
        AgentSessionId sessionId,
        String objective,
        RunConfigurationSnapshotRef configurationSnapshot) {

    public AgentRunRequest {
        agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId must not be null");
        agentDefinitionVersion =
                Objects.requireNonNull(agentDefinitionVersion, "agentDefinitionVersion must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        objective = requireObjective(objective);
        configurationSnapshot = Objects.requireNonNull(configurationSnapshot, "configurationSnapshot must not be null");
    }

    private static String requireObjective(String value) {
        String normalized =
                Objects.requireNonNull(value, "objective must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        return normalized;
    }
}
