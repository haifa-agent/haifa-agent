package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;

/** Trusted product context used to bind internal Git reads to the current Run. */
public record RepositoryRunContext(TenantRef tenant, String runRef, PrincipalRef actor) {
    public RepositoryRunContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        runRef = Objects.requireNonNull(runRef, "runRef must not be null").trim();
        if (runRef.isEmpty()) throw new IllegalArgumentException("runRef must not be blank");
        actor = Objects.requireNonNull(actor, "actor must not be null");
    }
}
