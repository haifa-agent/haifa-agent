package io.haifa.agent.project.workspace;

import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.Objects;

/**
 * Logical identity of one directory the host has authorized. It deliberately carries no mount,
 * binding or permission container: authorization facts live in the host location store and the
 * single authorized-directory record, while this record only anchors execution and file targets.
 */
public record Workspace(
        WorkspaceId id,
        ProjectId projectId,
        WorkspaceStatus status,
        WorkspaceRevision revision,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public Workspace {
        id = Objects.requireNonNull(id, "id must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        revision = Objects.requireNonNull(revision, "revision must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static Workspace provision(WorkspaceId id, ProjectId projectId, WorkspaceRevision revision, Instant at) {
        return new Workspace(id, projectId, WorkspaceStatus.PROVISIONING, revision, at, at, 0);
    }

    public Workspace activate(Instant at) {
        if (status != WorkspaceStatus.PROVISIONING)
            throw new IllegalStateException("only provisioning workspace can activate");
        return transition(WorkspaceStatus.ACTIVE, at);
    }

    public Workspace advanceRevision(WorkspaceRevision nextRevision, Instant at) {
        Objects.requireNonNull(nextRevision, "nextRevision must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (status != WorkspaceStatus.ACTIVE) throw new IllegalStateException("only active workspace can advance");
        if (nextRevision.sequence() != revision.sequence() + 1) {
            throw new IllegalArgumentException("workspace revision must advance by one");
        }
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("workspace change time must not move backwards");
        return new Workspace(id, projectId, status, nextRevision, createdAt, at, version + 1);
    }

    private Workspace transition(WorkspaceStatus target, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("workspace change time must not move backwards");
        return new Workspace(id, projectId, target, revision, createdAt, at, version + 1);
    }
}
