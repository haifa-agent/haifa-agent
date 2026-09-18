package io.haifa.agent.policy.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

public record ApprovalRequester(TenantRef tenant, PrincipalRef principal) {
    public ApprovalRequester {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }
}
