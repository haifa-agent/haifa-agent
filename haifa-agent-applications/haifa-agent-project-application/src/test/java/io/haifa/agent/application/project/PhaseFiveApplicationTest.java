package io.haifa.agent.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.admin.ArtifactView;
import io.haifa.agent.application.project.admin.FileChangeSetView;
import io.haifa.agent.application.project.admin.WorkspaceAdminView;
import io.haifa.agent.application.project.admin.WorkspaceBindingView;
import io.haifa.agent.application.project.admin.WorkspaceSnapshotView;
import io.haifa.agent.application.project.artifact.ArtifactExportRequest;
import io.haifa.agent.application.project.artifact.ArtifactExportService;
import io.haifa.agent.application.project.artifact.ArtifactExportSourceKind;
import io.haifa.agent.application.project.artifact.PublishedArtifactRequiredChecker;
import io.haifa.agent.application.project.checkpoint.InMemoryWorkspaceCheckpointStateStore;
import io.haifa.agent.application.project.checkpoint.WorkspaceCheckpointAccess;
import io.haifa.agent.application.project.checkpoint.WorkspaceCheckpointParticipant;
import io.haifa.agent.application.project.checkpoint.WorkspaceCheckpointPlan;
import io.haifa.agent.application.project.checkpoint.WorkspaceCheckpointResolver;
import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.SearchResult;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.snapshot.InMemoryWorkspaceSnapshotStore;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotEvidence;
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
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PhaseFiveApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void explicitPatchExportIsImmutableAndRequiredCheckerUsesPublishedState() throws Exception {
        var workspaceId = new WorkspaceId("workspace-1");
        var revision = WorkspaceRevision.initial("sha256:workspace");
        var workspaces = new InMemoryWorkspaceStore();
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-1"), "test"),
                        revision,
                        NOW)
                .activate(NOW));
        var artifactStore = new InMemoryArtifactStore();
        var payloadStore = new InMemoryArtifactPayloadStore();
        var ids = new AtomicInteger();
        var service = new ArtifactExportService(
                workspaces,
                new UnusedFileService(),
                new ArtifactService(artifactStore, payloadStore, () -> "artifact-" + ids.incrementAndGet(), () -> NOW),
                request -> request.actor().principalId().equals("author-1"));
        byte[] patch = "diff --git a/a b/a".getBytes(StandardCharsets.UTF_8);
        String hash = "sha256:"
                + java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(patch));
        var request = new ArtifactExportRequest(
                ArtifactExportSourceKind.PATCH,
                new ProjectRef("project-1"),
                workspaceId,
                ProjectPath.of("review/change.patch"),
                revision,
                hash,
                "change-1",
                "execution-1",
                patch,
                new ArtifactType("patch"),
                "Review patch",
                "text/x-diff",
                "review-v1",
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                new PrincipalRef("author-1", "user"),
                1024);
        patch[0] = 'X';

        var result = service.export(request);
        var published = artifactStore
                .find(new ArtifactId(result.artifact().artifactId()), new ArtifactVersion(1))
                .orElseThrow();
        assertThat(payloadStore.load(published.payload()).orElseThrow())
                .startsWith("diff --git".getBytes(StandardCharsets.UTF_8));

        var checker = new PublishedArtifactRequiredChecker(artifactStore);
        assertThat(checker.isSatisfied(null, decision(List.of(result.artifact()))))
                .isTrue();
        assertThat(checker.isSatisfied(null, decision(List.of(new ArtifactRef("missing", "patch", "1", "missing")))))
                .isFalse();
    }

    @Test
    void adminViewsCannotExposeHostPathTypes() {
        for (Class<?> view : List.of(
                WorkspaceAdminView.class,
                WorkspaceBindingView.class,
                WorkspaceSnapshotView.class,
                FileChangeSetView.class,
                ArtifactView.class)) {
            assertThat(view.getRecordComponents())
                    .extracting(RecordComponent::getType)
                    .doesNotContain(java.nio.file.Path.class, byte[].class);
        }
    }

    @Test
    void workspaceParticipantCapturesSnapshotAndReauthorizesOnResume() {
        var workspaceId = new WorkspaceId("workspace-checkpoint");
        var bindingStore = new InMemoryWorkspaceBindingStore();
        var workspaceStore = new InMemoryWorkspaceStore();
        var snapshotStore = new InMemoryWorkspaceSnapshotStore();
        var owner = new PrincipalRef("author-1", "user");
        var binding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("binding-checkpoint"),
                        new WorkspaceLocationRef("opaque-location"),
                        WorkspaceBindingMode.DIRECT,
                        owner,
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        "root-a",
                        NOW)
                .activate(NOW);
        bindingStore.create(binding);
        var workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), binding.id(), "test"),
                        WorkspaceRevision.initial("revision-a"),
                        NOW)
                .activate(NOW);
        workspaceStore.create(workspace);
        var evidence = new WorkspaceSnapshotEvidence("root-a", "manifest-a", null, null, null, null, null, true);
        var snapshots = new WorkspaceSnapshotService(
                workspaceStore,
                bindingStore,
                snapshotStore,
                (value, strategy) -> evidence,
                () -> "snapshot-1",
                () -> NOW);
        var authorized = new AtomicBoolean(true);
        WorkspaceCheckpointResolver resolver = new WorkspaceCheckpointResolver() {
            @Override
            public WorkspaceCheckpointPlan capturePlan(CapabilityCheckpointCaptureContext context) {
                return new WorkspaceCheckpointPlan(
                        workspaceId,
                        binding.id().value(),
                        WorkspaceSnapshotStrategy.METADATA_ONLY,
                        "local",
                        "1",
                        List.of(),
                        "run");
            }

            @Override
            public WorkspaceCheckpointAccess currentAccess(
                    CapabilityCheckpointRestoreContext context,
                    io.haifa.agent.application.project.checkpoint.WorkspaceCheckpointState state) {
                return new WorkspaceCheckpointAccess(workspace, binding, authorized.get());
            }
        };
        var participant = new WorkspaceCheckpointParticipant(
                snapshots,
                snapshotStore,
                new InMemoryWorkspaceCheckpointStateStore(),
                resolver,
                (value, strategy) -> evidence,
                new WorkspaceSnapshotValidator(),
                () -> "payload-1");
        var capture = new CapabilityCheckpointCaptureContext(
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                new TenantRef("tenant-1"),
                owner,
                java.util.Set.of(WorkspaceCheckpointParticipant.CAPABILITY_ID),
                "checkpoint-1",
                NOW);
        var reference = participant.capture(capture);
        var restore = new CapabilityCheckpointRestoreContext(
                capture.runId(),
                capture.sessionId(),
                capture.tenant(),
                capture.principal(),
                capture.enabledCapabilities(),
                NOW.plusSeconds(1));

        assertThat(participant.validate(reference, restore).valid()).isTrue();
        authorized.set(false);
        assertThat(participant.validate(reference, restore).code()).isEqualTo("PERMISSION_REVOKED");
    }

    private static FinalAnswerDecision decision(List<ArtifactRef> artifacts) {
        return new FinalAnswerDecision(AgentRunOutcome.SUCCESS, "done", "result", "1", Map.of(), artifacts, List.of());
    }

    private static final class UnusedFileService implements WorkspaceFileService {
        @Override
        public FileListPage list(FileListRequest request) {
            throw new AssertionError("not used");
        }

        @Override
        public FileMetadata stat(io.haifa.agent.project.path.WorkspacePath path, boolean includeHash) {
            throw new AssertionError("not used");
        }

        @Override
        public FileContent read(
                io.haifa.agent.project.path.WorkspacePath path, io.haifa.agent.project.filesystem.ReadOptions options) {
            throw new AssertionError("not used");
        }

        @Override
        public List<SearchResult> search(SearchRequest request) {
            throw new AssertionError("not used");
        }
    }
}
