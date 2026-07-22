package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBindingScope;
import java.util.List;
import java.util.Objects;

public record McpDiscoveryContext(
        TenantRef tenant, PrincipalRef principal, List<CredentialBindingScope> credentialScopeChain) {
    public McpDiscoveryContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        credentialScopeChain = List.copyOf(Objects.requireNonNull(credentialScopeChain, "credentialScopeChain"));
    }
}
