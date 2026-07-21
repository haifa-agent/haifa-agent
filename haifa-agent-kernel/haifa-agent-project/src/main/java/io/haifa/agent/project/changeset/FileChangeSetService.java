package io.haifa.agent.project.changeset;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.workspace.Workspace;
import java.util.List;
import java.util.Objects;

public final class FileChangeSetService {
    private final FileChangeSetStore store;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public FileChangeSetService(FileChangeSetStore store, IdentifierGenerator identifiers, TimeProvider time) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public FileChangeSet begin(
            Workspace workspace,
            String operationId,
            String runRef,
            String toolCallRef,
            PrincipalRef actor,
            String securityDecisionRef) {
        FileChangeSet changeSet = FileChangeSet.pending(
                new FileChangeSetId(identifiers.nextValue()),
                workspace.projectId(),
                workspace.id(),
                operationId,
                runRef,
                toolCallRef,
                workspace.revision(),
                actor,
                securityDecisionRef,
                time.now());
        store.create(changeSet);
        return changeSet;
    }

    public FileChangeSet markUnknown(FileChangeSet pending, List<FileChange> knownChanges, String detail) {
        FileChangeSet unknown = pending.unknown(knownChanges, detail, time.now());
        store.save(unknown, pending.version());
        return unknown;
    }

    public FileChangeSet beginReconciliation(FileChangeSet unknown) {
        FileChangeSet reconciling = unknown.beginReconciliation(time.now());
        store.save(reconciling, unknown.version());
        return reconciling;
    }

    public FileChangeSet reconcile(
            FileChangeSet reconciling, io.haifa.agent.project.workspace.WorkspaceRevision revision, String detail) {
        FileChangeSet reconciled = reconciling.reconciled(revision, detail, time.now());
        store.save(reconciled, reconciling.version());
        return reconciled;
    }

    public FileChangeSet fail(FileChangeSet current, String detail) {
        FileChangeSet failed = current.failed(detail, time.now());
        store.save(failed, current.version());
        return failed;
    }
}
