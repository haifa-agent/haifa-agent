package io.haifa.agent.application.project.product;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

public record TrustedProductCaller(TenantRef tenant, PrincipalRef principal) {
    public TrustedProductCaller {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }
}
