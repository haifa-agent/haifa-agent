package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One peer directory mounted into the host workspace scope. There is no main or attached role:
 * every directory carries its own logical {@link WorkspaceId}. The host real path remains inside
 * the host adapter and its protected Registry persistence; it must never reach Core DTOs, logs,
 * model prompts or Admin projections. This record does not grant user access.
 */
public record AuthorizedHostDirectory(WorkspaceId workspaceId, Path realPath) {

    public AuthorizedHostDirectory {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(realPath, "realPath must not be null");
        if (!realPath.isAbsolute()) {
            throw new IllegalArgumentException("realPath must be absolute: " + realPath);
        }
        realPath = realPath.normalize();
    }

    public static AuthorizedHostDirectory of(WorkspaceId workspaceId, Path realPath) {
        return new AuthorizedHostDirectory(workspaceId, realPath);
    }

    public boolean encloses(Path candidateRealPath) {
        return candidateRealPath.startsWith(realPath);
    }
}
