package io.haifa.agent.project.patch;

import io.haifa.agent.project.path.ProjectPath;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FilePatch(
        ProjectPath oldPath,
        ProjectPath newPath,
        List<PatchHunk> hunks,
        boolean oldEndsWithNewline,
        boolean newEndsWithNewline) {
    public FilePatch {
        if (oldPath == null && newPath == null) throw new IllegalArgumentException("file patch requires a path");
        hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks must not be null"));
        if (hunks.isEmpty()) throw new IllegalArgumentException("file patch requires at least one hunk");
    }

    public ProjectPath targetPath() {
        return newPath == null ? oldPath : newPath;
    }

    public ProjectPath sourcePath() {
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

    public Optional<ProjectPath> optionalOldPath() {
        return Optional.ofNullable(oldPath);
    }

    public Optional<ProjectPath> optionalNewPath() {
        return Optional.ofNullable(newPath);
    }
}
