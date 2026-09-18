package io.haifa.agent.execution.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record TrustedExecutionContext(
        TenantRef tenant,
        String runRef,
        PrincipalRef actor,
        Set<String> frozenCapabilities,
        ExecutionOrigin origin,
        Optional<ToolCallId> sourceToolCallId) {
    public TrustedExecutionContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        runRef = require(runRef, "runRef");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        frozenCapabilities =
                Set.copyOf(Objects.requireNonNull(frozenCapabilities, "frozenCapabilities must not be null"));
        origin = Objects.requireNonNull(origin, "origin must not be null");
        sourceToolCallId = Objects.requireNonNull(sourceToolCallId, "sourceToolCallId must not be null");
        if ((origin == ExecutionOrigin.RUNTIME_TOOL) != sourceToolCallId.isPresent()) {
            throw new IllegalArgumentException("runtime Tool origin and source Tool Call must be present together");
        }
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
