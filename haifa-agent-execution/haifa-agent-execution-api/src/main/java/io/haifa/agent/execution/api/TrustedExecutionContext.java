package io.haifa.agent.execution.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.util.Objects;
import java.util.Set;

public record TrustedExecutionContext(
        TenantRef tenant, String runRef, PrincipalRef actor, Set<String> frozenCapabilities, String policyDecisionRef) {
    /** @deprecated Trusted product integrations should provide the tenant explicitly. */
    @Deprecated(forRemoval = true)
    public TrustedExecutionContext(
            String runRef, PrincipalRef actor, Set<String> frozenCapabilities, String policyDecisionRef) {
        this(new TenantRef("local"), runRef, actor, frozenCapabilities, policyDecisionRef);
    }

    public TrustedExecutionContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        runRef = require(runRef, "runRef");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        frozenCapabilities =
                Set.copyOf(Objects.requireNonNull(frozenCapabilities, "frozenCapabilities must not be null"));
        policyDecisionRef = require(policyDecisionRef, "policyDecisionRef");
    }

    public boolean allows(String capability) {
        return frozenCapabilities.contains(capability);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
