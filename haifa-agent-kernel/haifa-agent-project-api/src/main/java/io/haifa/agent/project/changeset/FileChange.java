package io.haifa.agent.project.changeset;

import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;
import java.util.Optional;

public record FileChange(
        FileChangeType type, ProjectPath path, ProjectPath destination, FileVersion before, FileVersion after) {
    public FileChange {
        type = Objects.requireNonNull(type, "type must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
        if (type == FileChangeType.MOVE && destination == null) {
            throw new IllegalArgumentException("move requires a destination");
        }
        if (type != FileChangeType.MOVE && destination != null) {
            throw new IllegalArgumentException("only move may have a destination");
        }
        if (type == FileChangeType.CREATE && (before != null || after == null)) {
            throw new IllegalArgumentException("create requires only an after version");
        }
        if (type == FileChangeType.DELETE && (before == null || after != null)) {
            throw new IllegalArgumentException("delete requires only a before version");
        }
        if ((type == FileChangeType.REPLACE || type == FileChangeType.MOVE) && (before == null || after == null)) {
            throw new IllegalArgumentException(type + " requires before and after versions");
        }
    }

    public Optional<ProjectPath> optionalDestination() {
        return Optional.ofNullable(destination);
    }

    public Optional<FileVersion> optionalBefore() {
        return Optional.ofNullable(before);
    }

    public Optional<FileVersion> optionalAfter() {
        return Optional.ofNullable(after);
    }
}
