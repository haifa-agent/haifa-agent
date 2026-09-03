package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

/** Run identity passed to product tool adapters without exposing Runtime objects. */
public record ProjectToolCallContext(
        TenantRef tenant,
        WorkspaceId workspaceId,
        PrincipalRef actor,
        String runRef,
        String toolCallRef,
        String idempotencyKey,
        String policyDecisionRef) {
    public ProjectToolCallContext {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        runRef = required(runRef, "runRef");
        toolCallRef = required(toolCallRef, "toolCallRef");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        policyDecisionRef = required(policyDecisionRef, "policyDecisionRef");
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
