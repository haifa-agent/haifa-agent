package io.haifa.agent.model.core;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.ModelCapability;
import java.util.Objects;
import java.util.Set;

/** Trusted product query for models that may satisfy a new run. */
public record ModelAvailabilityRequest(
        TenantRef tenant, PrincipalRef principal, Set<ModelCapability> requiredCapabilities) {
    public ModelAvailabilityRequest {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        requiredCapabilities =
                Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null"));
    }
}
