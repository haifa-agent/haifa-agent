package io.haifa.agent.project.domain;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Project(
        ProjectId id,
        TenantRef tenant,
        PrincipalRef owner,
        String name,
        String description,
        ProjectStatus status,
        WorkspaceId defaultWorkspaceId,
        ProjectConfigurationRef configuration,
        Instant createdAt,
        Instant updatedAt,
        long version,
        Map<String, String> metadata) {

    public Project {
        id = Objects.requireNonNull(id, "id must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        owner = Objects.requireNonNull(owner, "owner must not be null");
        name = requireText(name, "name");
        description = Objects.requireNonNull(description, "description must not be null")
                .trim();
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        if (metadata.size() > 64) throw new IllegalArgumentException("metadata exceeds 64 entries");
    }

    public static Project create(
            ProjectId id,
            TenantRef tenant,
            PrincipalRef owner,
            String name,
            String description,
            ProjectConfigurationRef configuration,
            Instant at,
            Map<String, String> metadata) {
        return new Project(
                id, tenant, owner, name, description, ProjectStatus.ACTIVE, null, configuration, at, at, 0, metadata);
    }

    public Project archive(Instant at) {
        requireStatus(ProjectStatus.ACTIVE);
        return withStatus(ProjectStatus.ARCHIVED, at);
    }

    public Project activate(Instant at) {
        requireStatus(ProjectStatus.ARCHIVED);
        return withStatus(ProjectStatus.ACTIVE, at);
    }

    public Project assignDefaultWorkspace(WorkspaceId workspaceId, Instant at) {
        requireStatus(ProjectStatus.ACTIVE);
        requireChronological(at);
        return new Project(
                id,
                tenant,
                owner,
                name,
                description,
                status,
                Objects.requireNonNull(workspaceId, "workspaceId must not be null"),
                configuration,
                createdAt,
                at,
                version + 1,
                metadata);
    }

    public ProjectRef reference() {
        return new ProjectRef(id.value());
    }

    public Optional<WorkspaceId> defaultWorkspace() {
        return Optional.ofNullable(defaultWorkspaceId);
    }

    public Optional<ProjectConfigurationRef> configurationReference() {
        return Optional.ofNullable(configuration);
    }

    private Project withStatus(ProjectStatus target, Instant at) {
        requireChronological(at);
        return new Project(
                id,
                tenant,
                owner,
                name,
                description,
                target,
                defaultWorkspaceId,
                configuration,
                createdAt,
                at,
                version + 1,
                metadata);
    }

    private void requireStatus(ProjectStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected project status " + expected + " but was " + status);
        }
    }

    private void requireChronological(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) throw new IllegalArgumentException("project change time must not move backwards");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
