package io.haifa.agent.mcp.client;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

public record McpConnectionIdentity(TenantRef tenant, PrincipalRef principal) {
    public McpConnectionIdentity {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
    }
}
