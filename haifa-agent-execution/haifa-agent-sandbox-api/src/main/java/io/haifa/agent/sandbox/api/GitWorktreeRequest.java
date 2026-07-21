package io.haifa.agent.sandbox.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import java.util.Objects;

public record GitWorktreeRequest(
        WorkspaceId parentWorkspaceId,
        WorkspaceId childWorkspaceId,
        WorkspaceBindingId childBindingId,
        WorkspaceLocationRef childLocationRef,
        PrincipalRef owner,
        String baseCommit,
        WorkspaceCapabilitySet narrowedCapabilities,
        WorkspacePermissionSet narrowedPermissions) {
    public GitWorktreeRequest {
        parentWorkspaceId = Objects.requireNonNull(parentWorkspaceId, "parentWorkspaceId must not be null");
        childWorkspaceId = Objects.requireNonNull(childWorkspaceId, "childWorkspaceId must not be null");
        childBindingId = Objects.requireNonNull(childBindingId, "childBindingId must not be null");
        childLocationRef = Objects.requireNonNull(childLocationRef, "childLocationRef must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        baseCommit = Objects.requireNonNull(baseCommit, "baseCommit must not be null")
                .trim();
        if (!baseCommit.matches("[0-9a-fA-F]{7,64}"))
            throw new IllegalArgumentException("baseCommit must be a hex object id");
        narrowedCapabilities = Objects.requireNonNull(narrowedCapabilities, "narrowedCapabilities must not be null");
        narrowedPermissions = Objects.requireNonNull(narrowedPermissions, "narrowedPermissions must not be null");
    }
}
