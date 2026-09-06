package io.haifa.agent.project.workspace;

import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.Objects;

public record Workspace(
        WorkspaceId id,
        ProjectId projectId,
        WorkspacePurpose purpose,
        WorkspaceStatus status,
        WorkspaceRoot root,
        WorkspaceRevision revision,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public Workspace {
        id = Objects.requireNonNull(id, "id must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        root = Objects.requireNonNull(root, "root must not be null");
        revision = Objects.requireNonNull(revision, "revision must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static Workspace provision(
            WorkspaceId id,
            ProjectId projectId,
            WorkspacePurpose purpose,
            WorkspaceRoot root,
            WorkspaceRevision revision,
            Instant at) {
        return new Workspace(id, projectId, purpose, WorkspaceStatus.PROVISIONING, root, revision, at, at, 0);
    }

    public Workspace activate(Instant at) {
        if (status != WorkspaceStatus.PROVISIONING)
            throw new IllegalStateException("only provisioning workspace can activate");
        return transition(WorkspaceStatus.ACTIVE, at);
    }

    public Workspace beginRelease(Instant at) {
        if (status != WorkspaceStatus.ACTIVE) throw new IllegalStateException("only active workspace can release");
        return transition(WorkspaceStatus.RELEASING, at);
    }

    public Workspace released(Instant at) {
        if (status != WorkspaceStatus.RELEASING) throw new IllegalStateException("workspace is not releasing");
        return transition(WorkspaceStatus.RELEASED, at);
    }

    public Workspace advanceRevision(WorkspaceRevision nextRevision, Instant at) {
        Objects.requireNonNull(nextRevision, "nextRevision must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (status != WorkspaceStatus.ACTIVE) throw new IllegalStateException("only active workspace can advance");
        if (nextRevision.sequence() != revision.sequence() + 1) {
            throw new IllegalArgumentException("workspace revision must advance by one");
        }
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("workspace change time must not move backwards");
        return new Workspace(id, projectId, purpose, status, root, nextRevision, createdAt, at, version + 1);
    }

    private Workspace transition(WorkspaceStatus target, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("workspace change time must not move backwards");
        return new Workspace(id, projectId, purpose, target, root, revision, createdAt, at, version + 1);
    }
}
