package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import java.util.Objects;

/** Pure drift classifier. It never fetches, resets, checks out, or overwrites a workspace. */
public final class WorkspaceSnapshotValidator {
    public WorkspaceDriftDecision validate(ValidationRequest request) {
        WorkspaceSnapshot snapshot = request.snapshot();
        if (snapshot == null || snapshot.status() != WorkspaceSnapshotStatus.CAPTURED) {
            return decision(WorkspaceDriftKind.SNAPSHOT_MISSING_OR_CORRUPT, false, "SNAPSHOT_UNAVAILABLE");
        }
        if (request.workspace() == null || request.binding() == null) {
            return decision(WorkspaceDriftKind.BINDING_MISSING, false, "BINDING_OR_WORKSPACE_MISSING");
        }
        Workspace workspace = request.workspace();
        WorkspaceBinding binding = request.binding();
        if (!request.authorized()
                || binding.status() != WorkspaceBindingStatus.ACTIVE
                || workspace.status() != WorkspaceStatus.ACTIVE) {
            return decision(WorkspaceDriftKind.PERMISSION_REVOKED, false, "CURRENT_ACCESS_DENIED");
        }
        if (!snapshot.workspaceId().equals(workspace.id())
                || !snapshot.projectId().equals(workspace.projectId())) {
            return decision(WorkspaceDriftKind.BINDING_MISSING, false, "WORKSPACE_IDENTITY_CHANGED");
        }
        if (!snapshot.providerId().equals(request.providerId())
                || !snapshot.providerVersion().equals(request.providerVersion())) {
            return decision(WorkspaceDriftKind.PROVIDER_INCOMPATIBLE, false, "PROVIDER_VERSION_MISMATCH");
        }
        if (!snapshot.contentDigest()
                .equals(WorkspaceSnapshotDigests.digest(
                        snapshotWorkspace(snapshot, workspace), snapshot.strategy(), snapshot.evidence()))) {
            return decision(WorkspaceDriftKind.SNAPSHOT_MISSING_OR_CORRUPT, false, "SNAPSHOT_DIGEST_MISMATCH");
        }
        WorkspaceSnapshotEvidence current =
                Objects.requireNonNull(request.currentEvidence(), "currentEvidence must not be null");
        if (!current.consistent()) {
            return decision(WorkspaceDriftKind.MANUAL_RESOLUTION_REQUIRED, false, "CURRENT_VIEW_INCONSISTENT");
        }
        WorkspaceSnapshotEvidence captured = snapshot.evidence();
        boolean contentSame = captured.rootFingerprint().equals(current.rootFingerprint())
                && captured.manifestDigest().equals(current.manifestDigest());
        if (snapshot.strategy() == WorkspaceSnapshotStrategy.GIT_REFERENCE) {
            contentSame = contentSame
                    && Objects.equals(captured.gitRepositoryIdentity(), current.gitRepositoryIdentity())
                    && Objects.equals(captured.gitCommit(), current.gitCommit())
                    && Objects.equals(captured.gitTree(), current.gitTree())
                    && Objects.equals(captured.uncommittedPatchRef(), current.uncommittedPatchRef());
        }
        if (contentSame && snapshot.resultRevision().equals(workspace.revision())) {
            return decision(WorkspaceDriftKind.NO_DRIFT, true, "UNCHANGED");
        }
        if (contentSame) {
            return decision(WorkspaceDriftKind.SAFE_METADATA_DRIFT, true, "REVISION_ONLY_CHANGED");
        }
        if (snapshot.strategy() == WorkspaceSnapshotStrategy.FULL_COPY
                && binding.mode() == WorkspaceBindingMode.EPHEMERAL_COPY) {
            return decision(
                    WorkspaceDriftKind.MANUAL_RESOLUTION_REQUIRED, true, "RESTORE_TO_NEW_ISOLATED_COPY_REQUIRED");
        }
        return decision(WorkspaceDriftKind.CONTENT_DRIFT, false, "CONTENT_CHANGED");
    }

    private static Workspace snapshotWorkspace(WorkspaceSnapshot snapshot, Workspace current) {
        return new Workspace(
                snapshot.workspaceId(),
                snapshot.projectId(),
                current.purpose(),
                current.status(),
                current.root(),
                snapshot.resultRevision(),
                current.createdAt(),
                current.updatedAt(),
                current.version());
    }

    private static WorkspaceDriftDecision decision(
            WorkspaceDriftKind kind, boolean automaticRestoreAllowed, String reasonCode) {
        return new WorkspaceDriftDecision(kind, automaticRestoreAllowed, reasonCode);
    }

    public record ValidationRequest(
            WorkspaceSnapshot snapshot,
            Workspace workspace,
            WorkspaceBinding binding,
            boolean authorized,
            String providerId,
            String providerVersion,
            WorkspaceSnapshotEvidence currentEvidence) {
        public ValidationRequest {
            providerId = require(providerId, "providerId");
            providerVersion = require(providerVersion, "providerVersion");
        }

        private static String require(String value, String field) {
            String normalized =
                    Objects.requireNonNull(value, field + " must not be null").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
            return normalized;
        }
    }
}
