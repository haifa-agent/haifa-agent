package io.haifa.agent.application.project.workspace;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** CA product store; replacement changes the current mode and deletion revokes access. */
public interface WorkspaceAccessStore {
    WorkspaceAccess createIfAbsent(WorkspaceAccess access);

    WorkspaceAccess replace(WorkspaceAccess access);

    Optional<WorkspaceAccess> find(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId);

    List<WorkspaceAccess> list(TenantRef tenant, PrincipalRef principal);

    boolean delete(TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId);

    default WorkspaceAccess require(
            TenantRef tenant, PrincipalRef principal, WorkspaceId workspaceId, WorkspaceAccessMode requiredMode) {
        WorkspaceAccess access = find(tenant, principal, workspaceId)
                .orElseThrow(() -> new SecurityException("WORKSPACE_ACCESS_UNAVAILABLE"));
        if (!access.mode().allows(requiredMode)) {
            throw new SecurityException("WORKSPACE_ACCESS_MODE_DENIED");
        }
        return access;
    }
}
