package io.haifa.agent.project.mutation;

import io.haifa.agent.core.reference.PrincipalRef;
import java.util.Objects;

public record MutationContext(
        String operationId, String runRef, String toolCallRef, PrincipalRef actor, String securityDecisionRef) {
    public MutationContext {
        operationId = requireText(operationId, "operationId");
        runRef = normalize(runRef);
        toolCallRef = normalize(toolCallRef);
        actor = Objects.requireNonNull(actor, "actor must not be null");
        securityDecisionRef = requireText(securityDecisionRef, "securityDecisionRef");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
