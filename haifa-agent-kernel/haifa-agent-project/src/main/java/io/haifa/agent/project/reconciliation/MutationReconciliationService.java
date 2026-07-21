package io.haifa.agent.project.reconciliation;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.FileChangeSetStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class MutationReconciliationService {
    private final FileChangeSetStore changeSets;
    private final FileChangeSetService changeSetService;
    private final WorkspaceStore workspaces;
    private final MutationOutcomeProbe probe;
    private final TimeProvider time;

    public MutationReconciliationService(
            FileChangeSetStore changeSets,
            FileChangeSetService changeSetService,
            WorkspaceStore workspaces,
            MutationOutcomeProbe probe,
            TimeProvider time) {
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets must not be null");
        this.changeSetService = Objects.requireNonNull(changeSetService, "changeSetService must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public FileChangeSet reconcile(FileChangeSetId id) {
        FileChangeSet unknown =
                changeSets.find(id).orElseThrow(() -> new IllegalArgumentException("change set not found"));
        FileChangeSet reconciling = changeSetService.beginReconciliation(unknown);
        MutationProbeResult result = probe.probe(reconciling);
        if (result.status() != MutationProbeStatus.CONFIRMED) {
            return changeSetService.fail(reconciling, result.safeDetail());
        }
        Workspace workspace = workspaces
                .find(reconciling.workspaceId())
                .orElseThrow(() -> new IllegalStateException("workspace not found during reconciliation"));
        WorkspaceRevision revision;
        if (workspace.revision().sequence() > reconciling.baseRevision().sequence()) {
            revision = workspace.revision();
        } else {
            revision = new WorkspaceRevision(
                    workspace.revision().sequence() + 1,
                    "sha256:"
                            + hash((workspace.revision().digest() + "|reconcile|" + id.value())
                                    .getBytes(StandardCharsets.UTF_8)));
            Workspace advanced = workspace.advanceRevision(revision, time.now());
            workspaces.save(advanced, workspace.version());
        }
        return changeSetService.reconcile(reconciling, revision, result.safeDetail());
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
