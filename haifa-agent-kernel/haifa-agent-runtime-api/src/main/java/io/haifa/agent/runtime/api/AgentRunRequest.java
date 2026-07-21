package io.haifa.agent.runtime.api;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable identity-and-version request to start a reproducible run. */
public record AgentRunRequest(
        String idempotencyKey,
        AgentDefinitionId agentDefinitionId,
        Optional<AgentDefinitionVersion> requestedDefinitionVersion,
        String productProfileId,
        AgentSessionId sessionId,
        Optional<ProjectRef> project,
        String objective,
        List<ContentPart> inputs,
        RuntimeOverrides overrides) {

    public AgentRunRequest {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId must not be null");
        requestedDefinitionVersion =
                Objects.requireNonNull(requestedDefinitionVersion, "requestedDefinitionVersion must not be null");
        productProfileId = requireText(productProfileId, "productProfileId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        project = Objects.requireNonNull(project, "project must not be null");
        objective = requireText(objective, "objective");
        Objects.requireNonNull(inputs, "inputs must not be null");
        if (inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must not contain null");
        }
        inputs = List.copyOf(inputs);
        overrides = Objects.requireNonNull(overrides, "overrides must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
