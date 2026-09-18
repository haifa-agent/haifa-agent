package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One file operation of a parsed patch document. Old and new file references are logical
 * {@link WorkspacePath} values; there is no default workspace and a patch can never span two
 * logical workspaces.
 */
public record FilePatch(
        WorkspacePath oldPath,
        WorkspacePath newPath,
        List<PatchHunk> hunks,
        boolean oldEndsWithNewline,
        boolean newEndsWithNewline) {
    public FilePatch {
        if (oldPath == null && newPath == null) throw new IllegalArgumentException("file patch requires a path");
        if (oldPath != null && newPath != null && !oldPath.workspaceId().equals(newPath.workspaceId())) {
            throw new IllegalArgumentException("file patch cannot span two logical workspaces");
        }
        hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks must not be null"));
        if (hunks.isEmpty()) throw new IllegalArgumentException("file patch requires at least one hunk");
    }

    public WorkspacePath targetPath() {
        return newPath == null ? oldPath : newPath;
    }

    public WorkspacePath sourcePath() {
        return oldPath == null ? newPath : oldPath;
    }

    public boolean move() {
        return oldPath != null && newPath != null && !oldPath.equals(newPath);
    }

    public boolean creation() {
        return oldPath == null;
    }

    public boolean deletion() {
        return newPath == null;
    }

    public Optional<WorkspacePath> optionalOldPath() {
        return Optional.ofNullable(oldPath);
    }

    public Optional<WorkspacePath> optionalNewPath() {
        return Optional.ofNullable(newPath);
    }
}
