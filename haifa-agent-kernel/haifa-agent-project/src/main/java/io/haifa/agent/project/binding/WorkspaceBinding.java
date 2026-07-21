package io.haifa.agent.project.binding;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import java.time.Instant;
import java.util.Objects;

public record WorkspaceBinding(
        WorkspaceBindingId id,
        WorkspaceLocationRef locationRef,
        WorkspaceBindingMode mode,
        WorkspaceBindingStatus status,
        PrincipalRef owner,
        WorkspaceCapabilitySet capabilities,
        WorkspacePermissionSet permissions,
        String rootFingerprint,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public WorkspaceBinding {
        id = Objects.requireNonNull(id, "id must not be null");
        locationRef = Objects.requireNonNull(locationRef, "locationRef must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
        permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        rootFingerprint = Objects.requireNonNull(rootFingerprint, "rootFingerprint must not be null")
                .trim();
        if (rootFingerprint.isEmpty()) throw new IllegalArgumentException("rootFingerprint must not be blank");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (mode == WorkspaceBindingMode.READ_ONLY
                && permissions.values().stream().anyMatch(value -> value.name().matches("WRITE|DELETE|EXECUTE"))) {
            throw new IllegalArgumentException("read-only binding cannot grant mutation permissions");
        }
    }

    public static WorkspaceBinding provision(
            WorkspaceBindingId id,
            WorkspaceLocationRef locationRef,
            WorkspaceBindingMode mode,
            PrincipalRef owner,
            WorkspaceCapabilitySet capabilities,
            WorkspacePermissionSet permissions,
            String rootFingerprint,
            Instant at) {
        if (mode == WorkspaceBindingMode.COPY_ON_WRITE || mode == WorkspaceBindingMode.EPHEMERAL_COPY) {
            throw new UnsupportedOperationException("binding mode is not implemented in phase 1: " + mode);
        }
        return new WorkspaceBinding(
                id,
                locationRef,
                mode,
                WorkspaceBindingStatus.PROVISIONING,
                owner,
                capabilities,
                permissions,
                rootFingerprint,
                at,
                at,
                0);
    }

    public WorkspaceBinding activate(Instant at) {
        if (status != WorkspaceBindingStatus.PROVISIONING)
            throw new IllegalStateException("binding is not provisioning");
        return transition(WorkspaceBindingStatus.ACTIVE, at);
    }

    public WorkspaceBinding beginRelease(Instant at) {
        if (status != WorkspaceBindingStatus.ACTIVE) throw new IllegalStateException("binding is not active");
        return transition(WorkspaceBindingStatus.RELEASING, at);
    }

    public WorkspaceBinding released(Instant at) {
        if (status != WorkspaceBindingStatus.RELEASING) throw new IllegalStateException("binding is not releasing");
        return transition(WorkspaceBindingStatus.RELEASED, at);
    }

    private WorkspaceBinding transition(WorkspaceBindingStatus target, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("binding change time must not move backwards");
        return new WorkspaceBinding(
                id,
                locationRef,
                mode,
                target,
                owner,
                capabilities,
                permissions,
                rootFingerprint,
                createdAt,
                at,
                version + 1);
    }
}
