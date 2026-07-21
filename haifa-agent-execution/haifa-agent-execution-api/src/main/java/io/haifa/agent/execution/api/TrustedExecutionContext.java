package io.haifa.agent.execution.api;

import io.haifa.agent.core.reference.PrincipalRef;
import java.util.Objects;
import java.util.Set;

public record TrustedExecutionContext(
        String runRef, PrincipalRef actor, Set<String> frozenCapabilities, String policyDecisionRef) {
    public TrustedExecutionContext {
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
