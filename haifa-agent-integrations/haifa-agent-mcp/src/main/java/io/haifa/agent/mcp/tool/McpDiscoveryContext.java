package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

public record McpDiscoveryContext(TenantRef tenant, PrincipalRef principal) {
    public McpDiscoveryContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
    }

    public McpDiscoveryContext(TenantRef tenant, PrincipalRef principal, java.util.List<?> ignoredScopeChain) {
        this(tenant, principal);
    }
}
