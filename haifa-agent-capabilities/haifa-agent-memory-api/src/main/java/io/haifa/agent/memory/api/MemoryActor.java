package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/** Trusted caller identity supplied by the host; a Memory is readable and writable only by its owner. */
public record MemoryActor(TenantRef tenant, PrincipalRef principal) {
    public MemoryActor {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }

    public boolean owns(MemoryScope scope) {
        return tenant.equals(scope.tenant()) && principal.equals(scope.owner());
    }
}
