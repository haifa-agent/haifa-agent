package io.haifa.agent.project.snapshot;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import java.util.List;
import java.util.Objects;

public final class WorkspaceSnapshotService {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceSnapshotStore snapshots;
    private final WorkspaceSnapshotEvidenceProvider evidenceProvider;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public WorkspaceSnapshotService(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            WorkspaceSnapshotStore snapshots,
            WorkspaceSnapshotEvidenceProvider evidenceProvider,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.evidenceProvider = Objects.requireNonNull(evidenceProvider, "evidenceProvider must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public WorkspaceSnapshot capture(CaptureRequest request) {
        var replay = snapshots.findByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            WorkspaceSnapshot existing = replay.orElseThrow();
            if (!existing.workspaceId().equals(request.workspaceId())
                    || existing.strategy() != request.strategy()
                    || !existing.providerId().equals(request.providerId())
                    || !existing.providerVersion().equals(request.providerVersion())
                    || !existing.runRef().equals(request.runRef())
                    || !Objects.equals(existing.checkpointRef(), request.checkpointRef())
                    || !existing.changeSetRefs().equals(request.changeSetRefs())
                    || !existing.retentionPolicy().equals(request.retentionPolicy())) {
                throw new IllegalStateException("snapshot idempotency key was reused with different input");
            }
            return existing;
        }
        var workspace = workspaces
                .find(request.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("workspace not found"));
        var binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> new IllegalArgumentException("binding not found"));
        if (request.strategy() == WorkspaceSnapshotStrategy.FULL_COPY
                && binding.mode() != WorkspaceBindingMode.EPHEMERAL_COPY) {
            throw new UnsupportedOperationException("FULL_COPY requires EPHEMERAL_COPY workspace");
        }
        WorkspaceSnapshotEvidence evidence = evidenceProvider.capture(workspace, request.strategy());
        validateEvidence(request.strategy(), evidence);
        String digest = WorkspaceSnapshotDigests.digest(workspace, request.strategy(), evidence);
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
                new WorkspaceSnapshotId(ids.nextValue()),
                workspace.projectId(),
                workspace.id(),
                workspace.revision(),
                workspace.revision(),
                request.strategy(),
                request.providerId(),
                request.providerVersion(),
                WorkspaceSnapshotStatus.CAPTURED,
                evidence,
                digest,
                request.runRef(),
                request.checkpointRef(),
                request.changeSetRefs(),
                request.retentionPolicy(),
                time.now());
        snapshots.create(request.idempotencyKey(), snapshot);
        return snapshot;
    }

    private static void validateEvidence(WorkspaceSnapshotStrategy strategy, WorkspaceSnapshotEvidence evidence) {
        if (!evidence.consistent()) throw new IllegalStateException("workspace snapshot view is inconsistent");
        if (strategy == WorkspaceSnapshotStrategy.METADATA_ONLY && evidence.payload() != null) {
            throw new IllegalArgumentException("metadata-only snapshot must not contain a payload");
        }
        if (strategy == WorkspaceSnapshotStrategy.GIT_REFERENCE
                && (evidence.gitRepositoryIdentity() == null
                        || evidence.gitCommit() == null
                        || evidence.gitTree() == null)) {
            throw new IllegalArgumentException("Git reference snapshot requires repository, commit and tree evidence");
        }
        if (strategy == WorkspaceSnapshotStrategy.FULL_COPY && evidence.payload() == null) {
            throw new IllegalArgumentException("full-copy snapshot requires an external payload reference");
        }
    }

    public record CaptureRequest(
            String idempotencyKey,
            io.haifa.agent.project.workspace.WorkspaceId workspaceId,
            WorkspaceSnapshotStrategy strategy,
            String providerId,
            String providerVersion,
            String runRef,
            String checkpointRef,
            List<String> changeSetRefs,
            String retentionPolicy) {
        public CaptureRequest {
            idempotencyKey = require(idempotencyKey, "idempotencyKey");
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
            strategy = Objects.requireNonNull(strategy, "strategy must not be null");
            providerId = require(providerId, "providerId");
            providerVersion = require(providerVersion, "providerVersion");
            runRef = require(runRef, "runRef");
            checkpointRef = checkpointRef == null || checkpointRef.isBlank() ? null : checkpointRef.trim();
            changeSetRefs = List.copyOf(changeSetRefs);
            retentionPolicy = require(retentionPolicy, "retentionPolicy");
        }

        private static String require(String value, String field) {
            String normalized =
                    Objects.requireNonNull(value, field + " must not be null").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
            return normalized;
        }
    }
}
