package io.haifa.agent.policy.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

public record ApprovalResponder(TenantRef tenant, PrincipalRef principal) {
    public ApprovalResponder {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }
}
