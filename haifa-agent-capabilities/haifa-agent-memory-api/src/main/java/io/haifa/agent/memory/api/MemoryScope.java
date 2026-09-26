package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/** Isolation bucket. Tenant and owner always bound reads and writes; type and target select the bucket. */
public record MemoryScope(TenantRef tenant, PrincipalRef owner, MemoryScopeType type, String targetId) {
    public MemoryScope {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        targetId = MemoryValues.text(targetId, "targetId", 256);
        if (type == MemoryScopeType.USER && !targetId.equals(owner.principalId())) {
            throw new IllegalArgumentException("USER scope target must be the trusted owner principal");
        }
    }

    public static MemoryScope user(TenantRef tenant, PrincipalRef owner) {
        return new MemoryScope(tenant, owner, MemoryScopeType.USER, owner.principalId());
    }

    public static MemoryScope agent(TenantRef tenant, PrincipalRef owner, String agentId) {
        return new MemoryScope(tenant, owner, MemoryScopeType.AGENT, agentId);
    }

    public static MemoryScope session(TenantRef tenant, PrincipalRef owner, String sessionId) {
        return new MemoryScope(tenant, owner, MemoryScopeType.SESSION, sessionId);
    }
}
