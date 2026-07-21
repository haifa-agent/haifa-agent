package io.haifa.agent.model.core;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import java.util.Objects;
import java.util.Set;

/** Inputs to deterministic governed model selection. */
public record ModelSelectionRequest(
        TenantRef tenant,
        PrincipalRef principal,
        ModelDefinitionId modelId,
        Set<ModelCapability> requiredCapabilities) {
    public ModelSelectionRequest {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        requiredCapabilities =
                Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null"));
    }
}
