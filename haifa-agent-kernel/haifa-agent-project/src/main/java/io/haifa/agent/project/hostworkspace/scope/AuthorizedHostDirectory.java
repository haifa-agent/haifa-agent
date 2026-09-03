package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One peer authorized directory of the host workspace scope. There is no main or attached role:
 * every directory carries its own logical {@link WorkspaceId} and permission. The host real path
 * exists only inside Host Workspace Access and must never reach Core DTOs, persistence, logs or
 * Admin.
 */
public record AuthorizedHostDirectory(WorkspaceId workspaceId, Path realPath, HostDirectoryPermission permission) {

    public AuthorizedHostDirectory {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(realPath, "realPath must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        if (!realPath.isAbsolute()) {
            throw new IllegalArgumentException("realPath must be absolute: " + realPath);
        }
        realPath = realPath.normalize();
    }

    public static AuthorizedHostDirectory of(
            WorkspaceId workspaceId, Path realPath, HostDirectoryPermission permission) {
        return new AuthorizedHostDirectory(workspaceId, realPath, permission);
    }

    public boolean encloses(Path candidateRealPath) {
        return candidateRealPath.startsWith(realPath);
    }
}
