package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.snapshot.InMemoryWorkspaceSnapshotStore;
import io.haifa.agent.project.snapshot.WorkspaceDriftKind;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotEvidence;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotPayloadRef;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotService;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotStrategy;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotValidator;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkspaceSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void capturesIdempotentMetadataSnapshotAndClassifiesDriftWithoutMutation() {
        var fixture = fixture(WorkspaceBindingMode.DIRECT);
        WorkspaceSnapshotEvidence captured = evidence("root-a", "manifest-a", null);
        var service = new WorkspaceSnapshotService(
                fixture.workspaces,
                fixture.bindings,
                fixture.snapshots,
                (workspace, strategy) -> captured,
                () -> "snapshot-" + fixture.ids.incrementAndGet(),
                () -> NOW);
        var request = new WorkspaceSnapshotService.CaptureRequest(
                "checkpoint-1",
                fixture.workspace.id(),
                WorkspaceSnapshotStrategy.METADATA_ONLY,
                "local",
                "1",
                "run-1",
                "checkpoint-1",
                List.of("change-1"),
                "run-retention");

        var snapshot = service.capture(request);
        assertThat(service.capture(request)).isEqualTo(snapshot);
        assertThat(snapshot.evidence().payload()).isNull();
        var validator = new WorkspaceSnapshotValidator();
        assertThat(validator
                        .validate(new WorkspaceSnapshotValidator.ValidationRequest(
                                snapshot, fixture.workspace, fixture.binding, true, "local", "1", captured))
                        .kind())
                .isEqualTo(WorkspaceDriftKind.NO_DRIFT);
        assertThat(validator
                        .validate(new WorkspaceSnapshotValidator.ValidationRequest(
                                snapshot,
                                fixture.workspace,
                                fixture.binding,
                                true,
                                "local",
                                "1",
                                evidence("root-b", "manifest-b", null)))
                        .kind())
                .isEqualTo(WorkspaceDriftKind.CONTENT_DRIFT);
        assertThat(validator
                        .validate(new WorkspaceSnapshotValidator.ValidationRequest(
                                snapshot, fixture.workspace, fixture.binding, false, "local", "1", captured))
                        .kind())
                .isEqualTo(WorkspaceDriftKind.PERMISSION_REVOKED);
    }

    @Test
    void enforcesGitEvidenceConsistencyAndFullCopyIsolationBoundary() {
        var direct = fixture(WorkspaceBindingMode.DIRECT);
        var fullCopyService = new WorkspaceSnapshotService(
                direct.workspaces,
                direct.bindings,
                direct.snapshots,
                (workspace, strategy) ->
                        evidence("root", "manifest", new WorkspaceSnapshotPayloadRef("blob-1", "sha256:copy", 10)),
                () -> "snapshot-" + direct.ids.incrementAndGet(),
                () -> NOW);
        assertThatThrownBy(() ->
                        fullCopyService.capture(request(direct.workspace.id(), WorkspaceSnapshotStrategy.FULL_COPY)))
                .isInstanceOf(UnsupportedOperationException.class);

        var isolated = fixture(WorkspaceBindingMode.EPHEMERAL_COPY);
        var isolatedService = new WorkspaceSnapshotService(
                isolated.workspaces,
                isolated.bindings,
                isolated.snapshots,
                (workspace, strategy) ->
                        evidence("root", "manifest", new WorkspaceSnapshotPayloadRef("blob-1", "sha256:copy", 10)),
                () -> "snapshot-" + isolated.ids.incrementAndGet(),
                () -> NOW);
        assertThat(isolatedService
                        .capture(request(isolated.workspace.id(), WorkspaceSnapshotStrategy.FULL_COPY))
                        .strategy())
                .isEqualTo(WorkspaceSnapshotStrategy.FULL_COPY);

        var git = fixture(WorkspaceBindingMode.DIRECT);
        var invalidGit = new WorkspaceSnapshotService(
                git.workspaces,
                git.bindings,
                git.snapshots,
                (workspace, strategy) -> evidence("root", "manifest", null),
                () -> "snapshot-" + git.ids.incrementAndGet(),
                () -> NOW);
        assertThatThrownBy(
                        () -> invalidGit.capture(request(git.workspace.id(), WorkspaceSnapshotStrategy.GIT_REFERENCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkspaceSnapshotService.CaptureRequest request(
            WorkspaceId workspaceId, WorkspaceSnapshotStrategy strategy) {
        return new WorkspaceSnapshotService.CaptureRequest(
                "key-" + strategy, workspaceId, strategy, "local", "1", "run-1", "checkpoint-1", List.of(), "run");
    }

    private static WorkspaceSnapshotEvidence evidence(
            String root, String manifest, WorkspaceSnapshotPayloadRef payload) {
        return new WorkspaceSnapshotEvidence(root, manifest, null, null, null, null, payload, true);
    }

    private static Fixture fixture(WorkspaceBindingMode mode) {
        var ids = new AtomicInteger();
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("binding-" + mode),
                        new WorkspaceLocationRef("opaque-" + mode),
                        mode,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        "root-a",
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        Workspace workspace = Workspace.provision(
                        new WorkspaceId("workspace-" + mode),
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), binding.id(), "test"),
                        WorkspaceRevision.initial("revision-a"),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        return new Fixture(workspaces, bindings, new InMemoryWorkspaceSnapshotStore(), workspace, binding, ids);
    }

    private record Fixture(
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            InMemoryWorkspaceSnapshotStore snapshots,
            Workspace workspace,
            WorkspaceBinding binding,
            AtomicInteger ids) {}
}
