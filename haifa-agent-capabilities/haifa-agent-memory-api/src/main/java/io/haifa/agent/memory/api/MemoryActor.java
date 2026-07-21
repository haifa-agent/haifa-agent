package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Set;

public record MemoryActor(TenantRef tenant, PrincipalRef principal, Set<String> permissions) {
    public MemoryActor {
        tenant = Objects.requireNonNull(tenant);
        principal = Objects.requireNonNull(principal);
        permissions = Set.copyOf(Objects.requireNonNull(permissions));
    }
}
