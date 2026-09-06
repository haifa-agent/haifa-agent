package io.haifa.agent.project.workspace;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record WorkspacePermissionSet(Set<WorkspacePermission> values) {
    public WorkspacePermissionSet {
        values = Set.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }

    public static WorkspacePermissionSet readOnly() {
        return new WorkspacePermissionSet(EnumSet.of(
                WorkspacePermission.LIST,
                WorkspacePermission.STAT,
                WorkspacePermission.READ,
                WorkspacePermission.SEARCH));
    }

    public static WorkspacePermissionSet readWrite() {
        return new WorkspacePermissionSet(EnumSet.of(
                WorkspacePermission.LIST,
                WorkspacePermission.STAT,
                WorkspacePermission.READ,
                WorkspacePermission.SEARCH,
                WorkspacePermission.WRITE,
                WorkspacePermission.DELETE));
    }

    public static WorkspacePermissionSet readWriteExecute() {
        EnumSet<WorkspacePermission> permissions = EnumSet.copyOf(readWrite().values());
        permissions.add(WorkspacePermission.EXECUTE);
        return new WorkspacePermissionSet(permissions);
    }

    public boolean allows(WorkspacePermission permission) {
        return values.contains(permission);
    }

    public WorkspacePermissionSet intersect(WorkspacePermissionSet other) {
        EnumSet<WorkspacePermission> intersection = EnumSet.noneOf(WorkspacePermission.class);
        intersection.addAll(values);
        intersection.retainAll(other.values);
        return new WorkspacePermissionSet(intersection);
    }
}
