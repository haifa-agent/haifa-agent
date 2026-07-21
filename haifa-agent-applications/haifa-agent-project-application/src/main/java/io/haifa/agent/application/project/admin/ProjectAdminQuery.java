package io.haifa.agent.application.project.admin;

import io.haifa.agent.artifact.ArtifactStore;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionStore;
import io.haifa.agent.project.changeset.FileChangeSetStore;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotStore;
import io.haifa.agent.project.store.ProjectStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral, read-only admin projection. It never returns content, credentials, or host paths. */
public final class ProjectAdminQuery {
    private final ProjectStore projects;
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceSnapshotStore snapshots;
    private final FileChangeSetStore changeSets;
    private final ArtifactStore artifacts;
    private final ExecutionStore executions;
    private final AdminAuthorizer authorizer;

    public ProjectAdminQuery(
            ProjectStore projects,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            WorkspaceSnapshotStore snapshots,
            FileChangeSetStore changeSets,
            ArtifactStore artifacts,
            ExecutionStore executions,
            AdminAuthorizer authorizer) {
        this.projects = Objects.requireNonNull(projects);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.bindings = Objects.requireNonNull(bindings);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.changeSets = Objects.requireNonNull(changeSets);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.executions = Objects.requireNonNull(executions);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public AdminPage<WorkspaceAdminView> workspaces(PrincipalRef actor, ProjectId projectId, int offset, int limit) {
        requireAccess(actor, projectId);
        List<WorkspaceAdminView> values = workspaces.findByProject(projectId).stream()
                .sorted(Comparator.comparing(value -> value.id().value()))
                .map(workspace -> {
                    var binding = bindings.find(workspace.root().bindingId()).orElseThrow();
                    return new WorkspaceAdminView(
                            projectId.value(),
                            workspace.id().value(),
                            workspace.purpose().name(),
                            workspace.status().name(),
                            workspace.revision().sequence(),
                            workspace.revision().digest(),
                            new WorkspaceBindingView(
                                    binding.id().value(),
                                    "redacted:"
                                            + shortHash(binding.locationRef().value()),
                                    binding.mode().name(),
                                    binding.status().name(),
                                    binding.rootFingerprint(),
                                    binding.version()),
                            workspace.updatedAt());
                })
                .toList();
        return page(values, offset, limit);
    }

    public AdminPage<WorkspaceSnapshotView> snapshots(
            PrincipalRef actor,
            ProjectId projectId,
            WorkspaceId workspaceId,
            Instant from,
            Instant to,
            String status,
            int offset,
            int limit) {
        requireAccess(actor, projectId);
        List<WorkspaceSnapshotView> values = snapshots.findByWorkspace(workspaceId).stream()
                .filter(value -> value.projectId().equals(projectId))
                .filter(value ->
                        !value.createdAt().isBefore(from) && value.createdAt().isBefore(to))
                .filter(value -> status == null || value.status().name().equals(status))
                .sorted(Comparator.comparing(io.haifa.agent.project.snapshot.WorkspaceSnapshot::createdAt)
                        .thenComparing(value -> value.id().value()))
                .map(value -> new WorkspaceSnapshotView(
                        value.id().value(),
                        value.workspaceId().value(),
                        value.strategy().name(),
                        value.status().name(),
                        value.baseRevision().sequence(),
                        value.resultRevision().sequence(),
                        value.contentDigest(),
                        value.providerId() + "@" + value.providerVersion(),
                        value.runRef(),
                        value.checkpointRef(),
                        value.createdAt()))
                .toList();
        return page(values, offset, limit);
    }

    public AdminPage<FileChangeSetView> changeSets(
            PrincipalRef actor, ProjectId projectId, WorkspaceId workspaceId, int offset, int limit) {
        requireAccess(actor, projectId);
        List<FileChangeSetView> values = changeSets.findByWorkspace(workspaceId).stream()
                .filter(value -> value.projectId().equals(projectId))
                .sorted(Comparator.comparing(io.haifa.agent.project.changeset.FileChangeSet::createdAt)
                        .thenComparing(value -> value.id().value()))
                .map(value -> new FileChangeSetView(
                        value.id().value(),
                        value.workspaceId().value(),
                        value.status().name(),
                        value.baseRevision().sequence(),
                        value.optionalResultRevision()
                                .map(revision -> revision.sequence())
                                .orElse(null),
                        value.changes().size(),
                        value.runRef(),
                        value.createdAt()))
                .toList();
        return page(values, offset, limit);
    }

    public AdminPage<ArtifactView> artifacts(PrincipalRef actor, ProjectId projectId, int offset, int limit) {
        requireAccess(actor, projectId);
        List<ArtifactView> values = artifacts.findByProject(projectId.value()).stream()
                .map(value -> new ArtifactView(
                        value.id().value(),
                        value.version().value(),
                        value.type().value(),
                        value.title(),
                        value.status().name(),
                        value.payload().sha256(),
                        value.payload().byteCount(),
                        value.provenance().workspaceRef(),
                        value.provenance().runId().value(),
                        value.createdAt()))
                .toList();
        return page(values, offset, limit);
    }

    public Optional<ExecutionView> execution(PrincipalRef actor, ProjectId projectId, ExecutionId executionId) {
        requireAccess(actor, projectId);
        return executions
                .findResult(executionId)
                .map(value -> new ExecutionView(
                        value.id().value(),
                        value.status().name(),
                        value.exitCode(),
                        value.optionalFileChangeSetId().map(id -> id.value()).orElse(null),
                        value.optionalFailure().map(failure -> failure.code()).orElse(null),
                        value.startedAt(),
                        value.endedAt()));
    }

    private Project requireAccess(PrincipalRef actor, ProjectId projectId) {
        Project project = projects.find(projectId).orElseThrow(() -> new IllegalArgumentException("project not found"));
        if (!authorizer.canRead(actor, project)) throw new SecurityException("admin project read denied");
        return project;
    }

    private static <T> AdminPage<T> page(List<T> values, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid pagination");
        int start = Math.min(offset, values.size());
        int end = Math.min(start + limit, values.size());
        return new AdminPage<>(new ArrayList<>(values.subList(start, end)), offset, limit, end < values.size());
    }

    private static String shortHash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
