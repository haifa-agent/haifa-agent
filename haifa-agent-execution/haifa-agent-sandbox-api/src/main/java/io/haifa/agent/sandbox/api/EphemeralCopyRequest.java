package io.haifa.agent.sandbox.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import java.util.Objects;

public record EphemeralCopyRequest(
        WorkspaceId parentWorkspaceId,
        WorkspaceId childWorkspaceId,
        WorkspaceBindingId childBindingId,
        WorkspaceLocationRef childLocationRef,
        PrincipalRef owner,
        WorkspaceCapabilitySet narrowedCapabilities,
        WorkspacePermissionSet narrowedPermissions,
        WorkspaceCopyBudget budget) {
    public EphemeralCopyRequest {
        parentWorkspaceId = Objects.requireNonNull(parentWorkspaceId, "parentWorkspaceId must not be null");
        childWorkspaceId = Objects.requireNonNull(childWorkspaceId, "childWorkspaceId must not be null");
        childBindingId = Objects.requireNonNull(childBindingId, "childBindingId must not be null");
        childLocationRef = Objects.requireNonNull(childLocationRef, "childLocationRef must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        narrowedCapabilities = Objects.requireNonNull(narrowedCapabilities, "narrowedCapabilities must not be null");
        narrowedPermissions = Objects.requireNonNull(narrowedPermissions, "narrowedPermissions must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
    }
}
