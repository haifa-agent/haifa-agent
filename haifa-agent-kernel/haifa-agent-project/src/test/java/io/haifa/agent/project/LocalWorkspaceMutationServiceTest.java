package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.diff.DiffFile;
import io.haifa.agent.project.diff.DiffRequest;
import io.haifa.agent.project.diff.DiffService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.mutation.AuthorizedWorkspaceMutationService;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationErrorCode;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceWriteLease;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.patch.PatchApplyRequest;
import io.haifa.agent.project.patch.PatchConflictCode;
import io.haifa.agent.project.patch.PatchService;
import io.haifa.agent.project.patch.PatchValidationService;
import io.haifa.agent.project.patch.UnifiedPatchParser;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalMutationOutcomeProbe;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.LocalWorkspaceMutationService;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.quarantine.InMemoryQuarantineStore;
import io.haifa.agent.project.quarantine.QuarantineRestoreRequest;
import io.haifa.agent.project.reconciliation.MutationProbeStatus;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceMutationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void createsWritesMovesQuarantinesRestoresAndReplaysWithStableChangeSets() throws Exception {
        Files.writeString(root.resolve("a.txt"), "alpha\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture(WorkspaceBindingMode.DIRECT, WorkspacePermissionSet.readWrite());
        WorkspaceRevision initial = fixture.workspace().revision();

        var created = fixture.authorized()
                .create(new CreateFileRequest(
                        fixture.path("b.txt"),
                        bytes("bravo\n"),
                        MutationPrecondition.absent(initial),
                        context("create")));
        assertThat(created.status()).isEqualTo(FileChangeSetStatus.APPLIED);
        assertThat(created.atomic()).isTrue();
        assertThat(fixture.local().capabilities().atomicMoveAttempted()).isTrue();
        assertThat(fixture.local().capabilities().durableFileFlush()).isTrue();
        assertThat(Files.readString(root.resolve("b.txt"))).isEqualTo("bravo\n");
        Files.writeString(root.resolve(".haifa-write-orphan.tmp"), "managed temporary");
        var probe =
                new LocalMutationOutcomeProbe(fixture.workspaceStore(), fixture.bindingStore(), fixture.locations());
        assertThat(probe.probe(fixture.changeSets().find(created.changeSetId()).orElseThrow())
                        .status())
                .isEqualTo(MutationProbeStatus.CONFIRMED);
        assertThat(Files.exists(root.resolve(".haifa-write-orphan.tmp"))).isFalse();

        var replayed = fixture.authorized()
                .create(new CreateFileRequest(
                        fixture.path("b.txt"),
                        bytes("bravo\n"),
                        MutationPrecondition.absent(initial),
                        context("create")));
        assertThat(replayed.changeSetId()).isEqualTo(created.changeSetId());
        assertThat(replayed.replayed()).isTrue();
        assertThat(Files.readString(root.resolve("b.txt"))).isEqualTo("bravo\n");
        assertThatThrownBy(() -> fixture.authorized()
                        .create(new CreateFileRequest(
                                fixture.path("b.txt"),
                                bytes("different"),
                                MutationPrecondition.absent(initial),
                                context("create"))))
                .isInstanceOfSatisfying(WorkspaceMutationException.class, exception -> assertThat(exception.code())
                        .isEqualTo(MutationErrorCode.CONCURRENT_MODIFICATION));

        WorkspaceRevision revision = fixture.workspace().revision();
        var written = fixture.authorized()
                .write(new WriteFileRequest(
                        fixture.path("a.txt"),
                        bytes("updated\r\n"),
                        MutationPrecondition.existing(revision, hash("alpha\n")),
                        context("write")));
        revision = written.resultRevision();

        var moved = fixture.authorized()
                .move(new MoveFileRequest(
                        fixture.path("b.txt"),
                        fixture.path("c.txt"),
                        MutationPrecondition.existing(revision, hash("bravo\n")),
                        context("move")));
        revision = moved.resultRevision();
        assertThat(Files.exists(root.resolve("b.txt"))).isFalse();
        assertThat(Files.readString(root.resolve("c.txt"))).isEqualTo("bravo\n");

        var deleted = fixture.authorized()
                .delete(new DeleteFileRequest(
                        fixture.path("c.txt"),
                        MutationPrecondition.existing(revision, hash("bravo\n")),
                        context("delete")));
        assertThat(Files.exists(root.resolve("c.txt"))).isFalse();
        assertThat(fixture.quarantine().findByWorkspace(fixture.workspaceId())).hasSize(1);
        assertThat(fixture.files().list(WorkspacePath.root(fixture.workspaceId()), 20))
                .extracting(entry -> entry.metadata().path().projectPath().value())
                .doesNotContain(".haifa-quarantine");

        String token = fixture.quarantine()
                .findByWorkspace(fixture.workspaceId())
                .get(0)
                .token();
        var restored = fixture.local()
                .restore(new QuarantineRestoreRequest(token, fixture.path("restored.txt"), context("restore")));
        assertThat(restored.resultRevision().sequence()).isEqualTo(initial.sequence() + 5);
        assertThat(Files.readString(root.resolve("restored.txt"))).isEqualTo("bravo\n");
        assertThat(fixture.changeSets().findByWorkspace(fixture.workspaceId()))
                .extracting(value -> value.status())
                .containsOnly(FileChangeSetStatus.APPLIED);
        assertThat(deleted.changes()).hasSize(1);
    }

    @Test
    void rejectsWrongHashReadOnlyAndConcurrentLeaseWithoutOverwriting() throws Exception {
        Files.writeString(root.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
        Fixture fixture = fixture(WorkspaceBindingMode.DIRECT, WorkspacePermissionSet.readWrite());
        WorkspaceRevision revision = fixture.workspace().revision();

        assertThatThrownBy(() -> fixture.authorized()
                        .write(new WriteFileRequest(
                                fixture.path("a.txt"),
                                bytes("overwrite"),
                                MutationPrecondition.existing(revision, hash("wrong")),
                                context("wrong-hash"))))
                .isInstanceOfSatisfying(WorkspaceMutationException.class, exception -> assertThat(exception.code())
                        .isEqualTo(MutationErrorCode.CONTENT_HASH_CONFLICT));
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("alpha");

        try (WorkspaceWriteLease ignored = fixture.leases().acquire(fixture.workspaceId(), "holder")) {
            assertThatThrownBy(() -> fixture.authorized()
                            .create(new CreateFileRequest(
                                    fixture.path("blocked.txt"),
                                    bytes("blocked"),
                                    MutationPrecondition.absent(revision),
                                    context("blocked"))))
                    .isInstanceOfSatisfying(WorkspaceMutationException.class, exception -> assertThat(exception.code())
                            .isEqualTo(MutationErrorCode.WRITE_LEASE_UNAVAILABLE));
        }

        Fixture readOnly = fixture(
                WorkspaceBindingMode.READ_ONLY,
                WorkspacePermissionSet.readOnly(),
                "workspace-read-only",
                "binding-read-only");
        WorkspaceRevision readOnlyRevision = readOnly.workspace().revision();
        var create = new CreateFileRequest(
                readOnly.path("denied.txt"),
                bytes("denied"),
                MutationPrecondition.absent(readOnlyRevision),
                context("ro-create"));
        var write = new WriteFileRequest(
                readOnly.path("a.txt"),
                bytes("denied"),
                MutationPrecondition.existing(readOnlyRevision, hash("alpha")),
                context("ro-write"));
        var delete = new DeleteFileRequest(
                readOnly.path("a.txt"),
                MutationPrecondition.existing(readOnlyRevision, hash("alpha")),
                context("ro-delete"));
        var move = new MoveFileRequest(
                readOnly.path("a.txt"), readOnly.path("moved.txt"),
                MutationPrecondition.existing(readOnlyRevision, hash("alpha")), context("ro-move"));
        assertReadOnly(() -> readOnly.authorized().create(create));
        assertReadOnly(() -> readOnly.authorized().write(write));
        assertReadOnly(() -> readOnly.authorized().delete(delete));
        assertReadOnly(() -> readOnly.authorized().move(move));
        assertReadOnly(() -> readOnly.local().create(create));
        assertReadOnly(() -> readOnly.local().write(write));
        assertReadOnly(() -> readOnly.local().delete(delete));
        assertReadOnly(() -> readOnly.local().move(move));
    }

    @Test
    void rejectsMutationThroughSymbolicLinkParent() throws Exception {
        Path outside = Files.createTempDirectory("haifa-mutation-outside");
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
        }
        Fixture fixture = fixture(WorkspaceBindingMode.DIRECT, WorkspacePermissionSet.readWrite());
        assertThatThrownBy(() -> fixture.authorized()
                        .create(new CreateFileRequest(
                                fixture.path("linked/escape.txt"),
                                bytes("escape"),
                                MutationPrecondition.absent(fixture.workspace().revision()),
                                context("link"))))
                .isInstanceOfSatisfying(WorkspaceMutationException.class, exception -> assertThat(exception.code())
                        .isEqualTo(MutationErrorCode.PATH_DENIED));
        assertThat(Files.exists(outside.resolve("escape.txt"))).isFalse();
    }

    @Test
    void generatesParsesAndAppliesBoundedPatchWhilePreservingCrLfAndBom() throws Exception {
        String before = "\uFEFFone\r\ntwo\r\n";
        String after = "\uFEFFONE\r\ntwo\r\n";
        Files.writeString(root.resolve("bom.txt"), before, StandardCharsets.UTF_8);
        Fixture fixture = fixture(WorkspaceBindingMode.DIRECT, WorkspacePermissionSet.readWrite());

        var diff = new DiffService()
                .generate(new DiffRequest(
                        List.of(new DiffFile(ProjectPath.of("bom.txt"), before, after)), 10, 1024, 4096));
        var document = new UnifiedPatchParser(10, 100, 4096).parse(diff.unifiedDiff());
        assertThat(document.sha256()).isEqualTo(diff.sha256());
        var patchService = new PatchService(
                fixture.workspaceStore(),
                fixture.files(),
                fixture.authorized(),
                new PatchValidationService(10, 20, 100));
        var result = patchService.apply(new PatchApplyRequest(
                fixture.workspaceId(),
                document,
                fixture.workspace().revision(),
                Map.of(ProjectPath.of("bom.txt"), hash(before)),
                context("patch")));

        assertThat(result.complete()).isTrue();
        assertThat(result.appliedMutations()).hasSize(1);
        assertThat(Files.readString(root.resolve("bom.txt"), StandardCharsets.UTF_8))
                .isEqualTo(after);

        var stale = patchService.apply(new PatchApplyRequest(
                fixture.workspaceId(),
                document,
                WorkspaceRevision.initial("stale"),
                Map.of(ProjectPath.of("bom.txt"), hash(after)),
                context("stale-patch")));
        assertThat(stale.complete()).isFalse();
        assertThat(stale.conflicts())
                .extracting(value -> value.code())
                .containsExactly(PatchConflictCode.REVISION_CONFLICT);
    }

    @Test
    void rejectsTraversalDuplicateAndOverBudgetPatchesDeterministically() {
        var parser = new UnifiedPatchParser(2, 20, 1024);
        String traversal = "--- a/../escape.txt\n+++ b/../escape.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n";
        assertThatThrownBy(() -> parser.parse(traversal)).isInstanceOf(IllegalArgumentException.class);

        String duplicate = "--- a/a.txt\n+++ b/a.txt\n@@ -1,1 +1,1 @@\n-a\n+b\n"
                + "--- a/a.txt\n+++ b/a.txt\n@@ -1,1 +1,1 @@\n-b\n+c\n";
        assertThatThrownBy(() -> parser.parse(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new DiffService()
                        .generate(new DiffRequest(List.of(new DiffFile(ProjectPath.of("a.txt"), "a", "b")), 1, 10, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output budget");
        var sensitiveDiff = new DiffFile(ProjectPath.of("safe.txt"), "secret-before", "secret-after");
        assertThat(sensitiveDiff.toString()).doesNotContain("secret-before", "secret-after");
        assertThat(new io.haifa.agent.project.patch.PatchLine(
                                io.haifa.agent.project.patch.PatchLineType.ADD, "secret-patch-line")
                        .toString())
                .doesNotContain("secret-patch-line");
    }

    @Test
    void reportsStructuredPartialPatchResultWithoutClaimingBatchAtomicity() throws Exception {
        Files.writeString(root.resolve("first.txt"), "first\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("second.txt"), "second\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture(WorkspaceBindingMode.DIRECT, WorkspacePermissionSet.readWrite());
        var diff = new DiffService()
                .generate(new DiffRequest(
                        List.of(
                                new DiffFile(ProjectPath.of("first.txt"), "first\n", "FIRST\n"),
                                new DiffFile(ProjectPath.of("second.txt"), "second\n", "SECOND\n")),
                        10,
                        1024,
                        8192));
        var document = new UnifiedPatchParser(10, 100, 8192).parse(diff.unifiedDiff());
        var calls = new AtomicInteger();
        io.haifa.agent.project.mutation.WorkspaceMutationService failSecond =
                new io.haifa.agent.project.mutation.WorkspaceMutationService() {
                    @Override
                    public io.haifa.agent.project.mutation.MutationResult create(CreateFileRequest request) {
                        return fixture.authorized().create(request);
                    }

                    @Override
                    public io.haifa.agent.project.mutation.MutationResult write(WriteFileRequest request) {
                        if (calls.incrementAndGet() == 2) {
                            throw new WorkspaceMutationException(
                                    MutationErrorCode.IO_FAILURE, request.path(), "simulated provider rejection");
                        }
                        return fixture.authorized().write(request);
                    }

                    @Override
                    public io.haifa.agent.project.mutation.MutationResult delete(DeleteFileRequest request) {
                        return fixture.authorized().delete(request);
                    }

                    @Override
                    public io.haifa.agent.project.mutation.MutationResult move(MoveFileRequest request) {
                        return fixture.authorized().move(request);
                    }
                };
        var result = new PatchService(
                        fixture.workspaceStore(), fixture.files(), failSecond, new PatchValidationService(10, 20, 100))
                .apply(new PatchApplyRequest(
                        fixture.workspaceId(),
                        document,
                        fixture.workspace().revision(),
                        Map.of(
                                ProjectPath.of("first.txt"), hash("first\n"),
                                ProjectPath.of("second.txt"), hash("second\n")),
                        context("partial-patch")));

        assertThat(result.complete()).isFalse();
        assertThat(result.appliedMutations()).hasSize(1);
        assertThat(result.conflicts()).hasSize(1);
        assertThat(Files.readString(root.resolve("first.txt"))).isEqualTo("FIRST\n");
        assertThat(Files.readString(root.resolve("second.txt"))).isEqualTo("second\n");
    }

    private Fixture fixture(WorkspaceBindingMode mode, WorkspacePermissionSet permissions) {
        return fixture(mode, permissions, "workspace-1", "binding-1");
    }

    private Fixture fixture(
            WorkspaceBindingMode mode, WorkspacePermissionSet permissions, String workspaceValue, String bindingValue) {
        WorkspaceId workspaceId = new WorkspaceId(workspaceValue);
        WorkspaceBindingId bindingId = new WorkspaceBindingId(bindingValue);
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-" + workspaceValue);
        var bindingStore = new InMemoryWorkspaceBindingStore();
        var workspaceStore = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        mode,
                        new PrincipalRef("owner", "user"),
                        mode == WorkspaceBindingMode.READ_ONLY
                                ? WorkspaceCapabilitySet.readOnlyFiles()
                                : WorkspaceCapabilitySet.readWriteFiles(),
                        permissions,
                        LocalWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindingStore.create(binding);
        Workspace workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaceStore.create(workspace);
        var identifiers = new AtomicInteger();
        var idGenerator =
                (io.haifa.agent.common.id.IdentifierGenerator) () -> "phase2-" + identifiers.incrementAndGet();
        var changeSets = new InMemoryFileChangeSetStore();
        var changeSetService = new FileChangeSetService(changeSets, idGenerator, () -> NOW);
        var quarantine = new InMemoryQuarantineStore();
        var leases = new InMemoryWorkspaceWriteLeaseManager();
        var local = new LocalWorkspaceMutationService(
                workspaceStore,
                bindingStore,
                locations,
                SensitivePathPolicy.defaults(),
                leases,
                changeSets,
                changeSetService,
                quarantine,
                idGenerator,
                () -> NOW);
        var authorized = new AuthorizedWorkspaceMutationService(workspaceStore, bindingStore, local);
        var files = new io.haifa.agent.project.provider.local.LocalWorkspaceFileService(
                workspaceStore, bindingStore, locations, SensitivePathPolicy.defaults());
        return new Fixture(
                workspaceId,
                workspaceStore,
                bindingStore,
                locations,
                local,
                authorized,
                files,
                changeSets,
                quarantine,
                leases);
    }

    private static MutationContext context(String operationId) {
        return new MutationContext(operationId, "run-1", "tool-1", new PrincipalRef("actor", "user"), "allow-1");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(String value) throws Exception {
        return "sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
    }

    private static void assertReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(WorkspaceMutationException.class, exception -> assertThat(exception.code())
                        .isEqualTo(MutationErrorCode.READ_ONLY));
    }

    private record Fixture(
            WorkspaceId workspaceId,
            InMemoryWorkspaceStore workspaceStore,
            InMemoryWorkspaceBindingStore bindingStore,
            LocalWorkspaceLocationStore locations,
            LocalWorkspaceMutationService local,
            AuthorizedWorkspaceMutationService authorized,
            io.haifa.agent.project.provider.local.LocalWorkspaceFileService files,
            InMemoryFileChangeSetStore changeSets,
            InMemoryQuarantineStore quarantine,
            InMemoryWorkspaceWriteLeaseManager leases) {
        private Workspace workspace() {
            return workspaceStore.find(workspaceId).orElseThrow();
        }

        private WorkspacePath path(String value) {
            return new WorkspacePath(workspaceId, ProjectPath.of(value));
        }
    }
}
