package io.haifa.agent.project.hostworkspace.directory;

import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import java.util.Objects;

/** Path-redacted product/model projection of one registered authorized directory. */
public record AuthorizedDirectoryView(
        String workspaceRef, String safeDisplayName, WorkspaceAccessMode mode, AuthorizedDirectoryStatus status) {

    public AuthorizedDirectoryView {
        workspaceRef = required(workspaceRef, "workspaceRef");
        safeDisplayName = required(safeDisplayName, "safeDisplayName");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
