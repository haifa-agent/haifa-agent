package io.haifa.agent.project.changeset;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ObservedFileChangeService {
    private final WorkspaceStore workspaces;
    private final FileChangeSetStore store;
    private final FileChangeSetService changeSets;
    private final TimeProvider time;

    public ObservedFileChangeService(
            WorkspaceStore workspaces, FileChangeSetStore store, FileChangeSetService changeSets, TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public FileChangeSet record(
            Workspace workspace,
            String operationId,
            String runRef,
            String toolCallRef,
            PrincipalRef actor,
            String securityDecisionRef,
            List<FileChange> changes) {
        if (changes.isEmpty()) throw new IllegalArgumentException("observed changes must not be empty");
        FileChangeSet pending =
                changeSets.begin(workspace, operationId, runRef, toolCallRef, actor, securityDecisionRef);
        WorkspaceRevision revision = new WorkspaceRevision(
                workspace.revision().sequence() + 1,
                "sha256:"
                        + hash((workspace.revision().digest() + "|observed|"
                                        + pending.id().value() + "|" + changes)
                                .getBytes(StandardCharsets.UTF_8)));
        Workspace advanced = workspace.advanceRevision(revision, time.now());
        workspaces.save(advanced, workspace.version());
        FileChangeSet applied = pending.applied(revision, List.copyOf(changes), false, time.now());
        store.save(applied, pending.version());
        return applied;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
