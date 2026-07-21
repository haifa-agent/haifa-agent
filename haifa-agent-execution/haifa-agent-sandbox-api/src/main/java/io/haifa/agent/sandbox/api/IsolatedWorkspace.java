package io.haifa.agent.sandbox.api;

import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.time.Instant;
import java.util.Objects;

public record IsolatedWorkspace(
        WorkspaceId parentWorkspaceId,
        WorkspaceId childWorkspaceId,
        WorkspaceBindingId bindingId,
        WorkspaceLocationRef locationRef,
        WorkspaceBindingMode mode,
        WorkspaceRevision baseRevision,
        Instant createdAt) {
    public IsolatedWorkspace {
        parentWorkspaceId = Objects.requireNonNull(parentWorkspaceId, "parentWorkspaceId must not be null");
        childWorkspaceId = Objects.requireNonNull(childWorkspaceId, "childWorkspaceId must not be null");
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        locationRef = Objects.requireNonNull(locationRef, "locationRef must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        baseRevision = Objects.requireNonNull(baseRevision, "baseRevision must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
