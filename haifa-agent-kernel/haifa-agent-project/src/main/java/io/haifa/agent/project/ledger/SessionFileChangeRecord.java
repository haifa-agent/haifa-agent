package io.haifa.agent.project.ledger;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import java.time.Instant;
import java.util.Objects;

public record SessionFileChangeRecord(
        WorkspaceRootAlias rootAlias,
        ProjectPath path,
        ProjectPath sourcePath,
        FileChangeType type,
        String beforeHash,
        long beforeSize,
        String afterHash,
        long afterSize,
        String toolCallId,
        Instant timestamp) {

    public SessionFileChangeRecord {
        Objects.requireNonNull(rootAlias, "rootAlias must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (type == FileChangeType.MOVE && sourcePath == null) {
            throw new IllegalArgumentException("sourcePath is required for MOVE changes");
        }
    }

    public static SessionFileChangeRecord create(
            WorkspaceRootAlias rootAlias,
            ProjectPath path,
            String afterHash,
            long afterSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
                rootAlias, path, null, FileChangeType.CREATE, null, -1L, afterHash, afterSize, toolCallId, timestamp);
    }

    public static SessionFileChangeRecord create(
            WorkspaceRootAlias rootAlias, ProjectPath path, String afterHash, long afterSize, Instant timestamp) {
        return create(rootAlias, path, afterHash, afterSize, null, timestamp);
    }

    public static SessionFileChangeRecord replace(
            WorkspaceRootAlias rootAlias,
            ProjectPath path,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
                rootAlias,
                path,
                null,
                FileChangeType.REPLACE,
                beforeHash,
                beforeSize,
                afterHash,
                afterSize,
                toolCallId,
                timestamp);
    }

    public static SessionFileChangeRecord replace(
            WorkspaceRootAlias rootAlias,
            ProjectPath path,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            Instant timestamp) {
        return replace(rootAlias, path, beforeHash, beforeSize, afterHash, afterSize, null, timestamp);
    }

    public static SessionFileChangeRecord delete(
            WorkspaceRootAlias rootAlias,
            ProjectPath path,
            String beforeHash,
            long beforeSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
                rootAlias, path, null, FileChangeType.DELETE, beforeHash, beforeSize, null, -1L, toolCallId, timestamp);
    }

    public static SessionFileChangeRecord delete(
            WorkspaceRootAlias rootAlias, ProjectPath path, String beforeHash, long beforeSize, Instant timestamp) {
        return delete(rootAlias, path, beforeHash, beforeSize, null, timestamp);
    }

    public static SessionFileChangeRecord move(
            WorkspaceRootAlias rootAlias,
            ProjectPath sourcePath,
            ProjectPath targetPath,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
                rootAlias,
                targetPath,
                sourcePath,
                FileChangeType.MOVE,
                beforeHash,
                beforeSize,
                afterHash,
                afterSize,
                toolCallId,
                timestamp);
    }

    public static SessionFileChangeRecord move(
            WorkspaceRootAlias rootAlias,
            ProjectPath sourcePath,
            ProjectPath targetPath,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            Instant timestamp) {
        return move(rootAlias, sourcePath, targetPath, beforeHash, beforeSize, afterHash, afterSize, null, timestamp);
    }
}
