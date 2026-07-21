package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Set;

public record MemoryScope(
        TenantRef tenant,
        PrincipalRef owner,
        MemoryScopeType type,
        String targetId,
        MemoryVisibility visibility,
        Set<MemorySecurityLabel> securityLabels) {
    public MemoryScope {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        targetId = MemoryValues.text(targetId, "targetId", 256);
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        if (type == MemoryScopeType.USER && !targetId.equals(owner.principalId())) {
            throw new IllegalArgumentException("USER scope target must be the trusted owner principal");
        }
    }
}
