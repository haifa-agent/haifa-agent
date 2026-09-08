package io.haifa.agent.application.project.workspace;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

/** The only durable CA authorization relation for one owner and workspace. */
public record WorkspaceAccess(
        TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId, WorkspaceAccessMode mode) {
    public WorkspaceAccess {
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
    }
}
