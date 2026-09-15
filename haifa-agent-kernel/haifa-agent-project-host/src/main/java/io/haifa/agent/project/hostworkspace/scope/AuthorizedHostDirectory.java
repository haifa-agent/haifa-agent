package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One peer authorized directory of the host workspace scope. There is no main or attached role:
 * every directory carries its own logical {@link WorkspaceId}. This host-only record must not be
 * serialized into public DTOs, Store payloads, logs or model output, and it does not grant user
 * access; the CA host may deliberately render an authorized canonical rootPath into the approved
 * {@code <workspace_paths>} model prompt and successful {@code workspace_attach} result, but never
 * the physical fingerprint.
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
