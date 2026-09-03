package io.haifa.agent.project.provider.local.scope;

import io.haifa.agent.project.path.WorkspacePath;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Short-lived result of resolving one absolute tool input against the local workspace scope. It is a
 * provider-local value only: it must never enter Core DTOs, persistence records, logs or Admin
 * views, and it does not survive the tool call that produced it.
 *
 * @param hostPath the physical path to operate on: the verified real path for existing targets, the
 *     normalized path for targets that do not exist yet
 */
public record ResolvedAuthorizedPath(
        String absoluteInput, LocalAllowedDirectory directory, WorkspacePath workspacePath, Path hostPath) {

    public ResolvedAuthorizedPath {
        Objects.requireNonNull(absoluteInput, "absoluteInput must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(workspacePath, "workspacePath must not be null");
        Objects.requireNonNull(hostPath, "hostPath must not be null");
    }
}
