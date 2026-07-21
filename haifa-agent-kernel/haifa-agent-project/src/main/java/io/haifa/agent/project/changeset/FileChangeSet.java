package io.haifa.agent.project.changeset;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FileChangeSet(
        FileChangeSetId id,
        ProjectId projectId,
        WorkspaceId workspaceId,
        String operationId,
        String runRef,
        String toolCallRef,
        WorkspaceRevision baseRevision,
        WorkspaceRevision resultRevision,
        List<FileChange> changes,
        FileChangeSetStatus status,
        PrincipalRef actor,
        String securityDecisionRef,
        boolean atomic,
        String reconciliationDetail,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public FileChangeSet {
        id = Objects.requireNonNull(id, "id must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        operationId = requireText(operationId, "operationId");
        runRef = normalizeOptional(runRef);
        toolCallRef = normalizeOptional(toolCallRef);
        baseRevision = Objects.requireNonNull(baseRevision, "baseRevision must not be null");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
        status = Objects.requireNonNull(status, "status must not be null");
        actor = Objects.requireNonNull(actor, "actor must not be null");
        securityDecisionRef = requireText(securityDecisionRef, "securityDecisionRef");
        reconciliationDetail = normalizeOptional(reconciliationDetail);
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if ((status == FileChangeSetStatus.APPLIED || status == FileChangeSetStatus.RECONCILED)
                && resultRevision == null) {
            throw new IllegalArgumentException("successful change set requires a result revision");
        }
    }

    public static FileChangeSet pending(
            FileChangeSetId id,
            ProjectId projectId,
            WorkspaceId workspaceId,
            String operationId,
            String runRef,
            String toolCallRef,
            WorkspaceRevision baseRevision,
            PrincipalRef actor,
            String securityDecisionRef,
            Instant at) {
        return new FileChangeSet(
                id,
                projectId,
                workspaceId,
                operationId,
                runRef,
                toolCallRef,
                baseRevision,
                null,
                List.of(),
                FileChangeSetStatus.PENDING,
                actor,
                securityDecisionRef,
                false,
                null,
                at,
                at,
                0);
    }

    public FileChangeSet applied(
            WorkspaceRevision resultRevision, List<FileChange> changes, boolean atomic, Instant at) {
        requireStatus(FileChangeSetStatus.PENDING);
        return transition(FileChangeSetStatus.APPLIED, resultRevision, changes, atomic, null, at);
    }

    public FileChangeSet unknown(List<FileChange> knownChanges, String detail, Instant at) {
        requireStatus(FileChangeSetStatus.PENDING);
        return transition(FileChangeSetStatus.UNKNOWN, null, knownChanges, false, detail, at);
    }

    public FileChangeSet beginReconciliation(Instant at) {
        requireStatus(FileChangeSetStatus.UNKNOWN);
        return transition(FileChangeSetStatus.RECONCILING, null, changes, false, reconciliationDetail, at);
    }

    public FileChangeSet reconciled(WorkspaceRevision revision, String detail, Instant at) {
        requireStatus(FileChangeSetStatus.RECONCILING);
        return transition(FileChangeSetStatus.RECONCILED, revision, changes, atomic, detail, at);
    }

    public FileChangeSet failed(String detail, Instant at) {
        if (status != FileChangeSetStatus.PENDING && status != FileChangeSetStatus.RECONCILING) {
            throw new IllegalStateException("change set cannot fail from " + status);
        }
        return transition(FileChangeSetStatus.FAILED, null, changes, false, detail, at);
    }

    public Optional<WorkspaceRevision> optionalResultRevision() {
        return Optional.ofNullable(resultRevision);
    }

    private FileChangeSet transition(
            FileChangeSetStatus target,
            WorkspaceRevision revision,
            List<FileChange> nextChanges,
            boolean nextAtomic,
            String detail,
            Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("change time must not move backwards");
        return new FileChangeSet(
                id,
                projectId,
                workspaceId,
                operationId,
                runRef,
                toolCallRef,
                baseRevision,
                revision,
                nextChanges,
                target,
                actor,
                securityDecisionRef,
                nextAtomic,
                detail,
                createdAt,
                at,
                version + 1);
    }

    private void requireStatus(FileChangeSetStatus expected) {
        if (status != expected) throw new IllegalStateException("expected " + expected + " but was " + status);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
