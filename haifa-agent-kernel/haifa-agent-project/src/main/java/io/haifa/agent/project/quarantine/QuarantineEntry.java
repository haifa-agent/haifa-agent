package io.haifa.agent.project.quarantine;

import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public record QuarantineEntry(
        String token,
        WorkspaceId workspaceId,
        String operationId,
        ProjectPath originalPath,
        FileVersion version,
        Instant quarantinedAt,
        Instant retainUntil) {
    public QuarantineEntry {
        token = requireText(token, "token");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        operationId = requireText(operationId, "operationId");
        originalPath = Objects.requireNonNull(originalPath, "originalPath must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt must not be null");
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil must not be null");
        if (!retainUntil.isAfter(quarantinedAt)) {
            throw new IllegalArgumentException("retainUntil must be after quarantinedAt");
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
