package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Stable tenant identity, including the local single-user tenant. */
public record TenantRef(String tenantId) {
    public TenantRef {
        tenantId = requireText(tenantId, "tenantId");
    }
}
