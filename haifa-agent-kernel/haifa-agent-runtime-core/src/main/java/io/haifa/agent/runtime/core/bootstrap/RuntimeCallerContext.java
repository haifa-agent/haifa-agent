package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/** Trusted identity supplied by the hosting boundary, never by a start payload. */
public record RuntimeCallerContext(TenantRef tenant, PrincipalRef principal) {
    public RuntimeCallerContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
    }
}
