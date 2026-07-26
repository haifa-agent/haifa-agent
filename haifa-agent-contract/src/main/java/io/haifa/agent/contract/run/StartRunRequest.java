package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.ContentPartDto;
import io.haifa.agent.contract.common.CorrelationId;
import io.haifa.agent.contract.common.IdempotencyKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** External start intent. Tenant, principal and configuration snapshots are never accepted here. */
public record StartRunRequest(
        IdempotencyKey idempotencyKey,
        String agentDefinitionId,
        Optional<String> requestedDefinitionVersion,
        String productProfileId,
        String sessionId,
        Optional<String> projectRef,
        String objective,
        List<ContentPartDto> inputs) {
    public StartRunRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        agentDefinitionId = CorrelationId.requireText(agentDefinitionId, "agentDefinitionId", 256);
        requestedDefinitionVersion = boundedOptional(requestedDefinitionVersion, "requestedDefinitionVersion", 64);
        productProfileId = CorrelationId.requireText(productProfileId, "productProfileId", 256);
        sessionId = CorrelationId.requireText(sessionId, "sessionId", 256);
        projectRef = boundedOptional(projectRef, "projectRef", 512);
        objective = CorrelationId.requireText(objective, "objective", 65_536);
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        if (inputs.size() > 100 || inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must contain at most 100 non-null parts");
        }
    }

    private static Optional<String> boundedOptional(Optional<String> value, String field, int maximumLength) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(item -> CorrelationId.requireText(item, field, maximumLength));
    }
}
