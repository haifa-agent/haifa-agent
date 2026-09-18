package io.haifa.agent.project.ledger;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.WorkspacePath;
import java.time.Instant;
import java.util.Objects;

public record SessionFileChangeRecord(
        WorkspacePath path,
        WorkspacePath sourcePath,
        FileChangeType type,
        String beforeHash,
        long beforeSize,
        String afterHash,
        long afterSize,
        String toolCallId,
        Instant timestamp) {

    public SessionFileChangeRecord {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (type == FileChangeType.MOVE && sourcePath == null) {
            throw new IllegalArgumentException("sourcePath is required for MOVE changes");
        }
        if (type == FileChangeType.MOVE && !sourcePath.workspaceId().equals(path.workspaceId())) {
            throw new IllegalArgumentException("MOVE changes cannot span two logical workspaces");
        }
    }

    public static SessionFileChangeRecord create(
            WorkspacePath path, String afterHash, long afterSize, String toolCallId, Instant timestamp) {
        return new SessionFileChangeRecord(
                path, null, FileChangeType.CREATE, null, -1L, afterHash, afterSize, toolCallId, timestamp);
    }

    public static SessionFileChangeRecord create(
            WorkspacePath path, String afterHash, long afterSize, Instant timestamp) {
        return create(path, afterHash, afterSize, null, timestamp);
    }

    public static SessionFileChangeRecord replace(
            WorkspacePath path,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
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
            WorkspacePath path,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            Instant timestamp) {
        return replace(path, beforeHash, beforeSize, afterHash, afterSize, null, timestamp);
    }

    public static SessionFileChangeRecord delete(
            WorkspacePath path, String beforeHash, long beforeSize, String toolCallId, Instant timestamp) {
        return new SessionFileChangeRecord(
                path, null, FileChangeType.DELETE, beforeHash, beforeSize, null, -1L, toolCallId, timestamp);
    }

    public static SessionFileChangeRecord delete(
            WorkspacePath path, String beforeHash, long beforeSize, Instant timestamp) {
        return delete(path, beforeHash, beforeSize, null, timestamp);
    }

    public static SessionFileChangeRecord move(
            WorkspacePath sourcePath,
            WorkspacePath targetPath,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            String toolCallId,
            Instant timestamp) {
        return new SessionFileChangeRecord(
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
            WorkspacePath sourcePath,
            WorkspacePath targetPath,
            String beforeHash,
            long beforeSize,
            String afterHash,
            long afterSize,
            Instant timestamp) {
        return move(sourcePath, targetPath, beforeHash, beforeSize, afterHash, afterSize, null, timestamp);
    }
}
