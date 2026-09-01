package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.delivery.CodingChangeReviewArtifact;
import io.haifa.agent.application.project.product.coding.delivery.OnDemandChangeReviewService;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationCapabilities;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRoot;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootRegistry;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.root.WorkspaceRootStrategy;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiRootWorkspaceSimplificationE2ETest {

    @TempDir
    Path tempDir;

    private Path mainDir;
    private Path docsDir;
    private Path configDir;

    private LocalWorkspaceRootRegistry registry;
    private InMemorySessionChangeLedger ledger;
    private LocalFileToolOperations operations;
    private WorkspaceId workspaceId;
    private OnDemandChangeReviewService onDemandReview;

    @BeforeEach
    void setUp() throws Exception {
        mainDir = tempDir.resolve("main");
        docsDir = tempDir.resolve("docs");
        configDir = tempDir.resolve("config");

        Files.createDirectories(mainDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(configDir);

        // Pre-populate some files
        Files.writeString(mainDir.resolve("App.java"), "public class App {}", StandardCharsets.UTF_8);
        Files.writeString(docsDir.resolve("guide.md"), "# Guide", StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve("app.yml"), "env: prod", StandardCharsets.UTF_8);

        LocalWorkspaceRoot mainRoot = LocalWorkspaceRoot.of(
                WorkspaceRootAlias.MAIN, mainDir, WorkspaceRootPermission.READ_WRITE, WorkspaceRootStrategy.GIT, false);
        LocalWorkspaceRoot docsRoot = LocalWorkspaceRoot.of(
                WorkspaceRootAlias.of("docs"),
                docsDir,
                WorkspaceRootPermission.READ_ONLY,
                WorkspaceRootStrategy.PLAIN,
                false);
        LocalWorkspaceRoot configRoot = LocalWorkspaceRoot.of(
                WorkspaceRootAlias.of("config"),
                configDir,
                WorkspaceRootPermission.READ_WRITE,
                WorkspaceRootStrategy.PLAIN,
                false);

        registry = LocalWorkspaceRootRegistry.builder()
                .addRoot(mainRoot)
                .addRoot(docsRoot)
                .addRoot(configRoot)
                .build();

        ledger = new InMemorySessionChangeLedger();
        onDemandReview = new OnDemandChangeReviewService(registry, ledger);

        workspaceId = new WorkspaceId("ws-e2e");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("bind-e2e");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("loc-e2e");

        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, mainDir);

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        LocalWorkspaceLocationStore.fingerprintFor(mainDir),
                        now)
                .activate(now);
        bindings.create(binding);

        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("proj-e2e"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));

        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var changeSets = new InMemoryFileChangeSetStore();

        WorkspaceMutationProvider mutationProvider = new WorkspaceMutationProvider() {
            @Override
            public String providerId() {
                return "e2e-mutation-provider";
            }

            @Override
            public WorkspaceMutationCapabilities capabilities() {
                return new WorkspaceMutationCapabilities(true, true, "case-sensitive-default");
            }

            @Override
            public MutationResult create(CreateFileRequest request) {
                try {
                    Path target = mainDir;
                    for (String segment : request.path().projectPath().segments()) target = target.resolve(segment);
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.write(target, request.content());
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:create-result");
                    return new MutationResult(
                            new FileChangeSetId("cs-1"),
                            FileChangeSetStatus.APPLIED,
                            before,
                            after,
                            List.of(new FileChange(
                                    FileChangeType.CREATE,
                                    request.path().projectPath(),
                                    null,
                                    null,
                                    new FileVersion(FileType.FILE, request.content().length, "sha256:create"))),
                            true,
                            false);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public MutationResult write(WriteFileRequest request) {
                try {
                    Path target = mainDir;
                    for (String segment : request.path().projectPath().segments()) target = target.resolve(segment);
                    Files.write(target, request.content());
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:write-result");
                    return new MutationResult(
                            new FileChangeSetId("cs-1"),
                            FileChangeSetStatus.APPLIED,
                            before,
                            after,
                            List.of(new FileChange(
                                    FileChangeType.REPLACE,
                                    request.path().projectPath(),
                                    null,
                                    new FileVersion(FileType.FILE, 0, "sha256:0"),
                                    new FileVersion(FileType.FILE, request.content().length, "sha256:w"))),
                            true,
                            false);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                try {
                    Path target = mainDir;
                    for (String segment : request.path().projectPath().segments()) target = target.resolve(segment);
                    Files.deleteIfExists(target);
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:delete-result");
                    return new MutationResult(
                            new FileChangeSetId("cs-1"),
                            FileChangeSetStatus.APPLIED,
                            before,
                            after,
                            List.of(new FileChange(
                                    FileChangeType.DELETE,
                                    request.path().projectPath(),
                                    null,
                                    new FileVersion(FileType.FILE, 0, "sha256:0"),
                                    null)),
                            true,
                            false);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                try {
                    Path src = mainDir;
                    for (String segment : request.source().projectPath().segments()) src = src.resolve(segment);
                    Path dst = mainDir;
                    for (String segment : request.destination().projectPath().segments()) dst = dst.resolve(segment);
                    Files.move(src, dst);
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:move-result");
                    return new MutationResult(
                            new FileChangeSetId("cs-1"),
                            FileChangeSetStatus.APPLIED,
                            before,
                            after,
                            List.of(),
                            true,
                            false);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };

        operations = new LocalFileToolOperations(
                workspaces, files, mutationProvider, () -> "id-1", changeSets, registry, ledger);
    }

    @Test
    void e2e01_multiRootAuthorizationAndReadOnlyPermissionIsolation() {
        // 1. Read from main root
        ToolResult readMain = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.read", "1.0", Map.of("path", "App.java")));
        assertThat(readMain.successful()).isTrue();
        assertThat((String) readMain.structuredData().get("content")).contains("public class App");

        // 2. Write to docs root (READ_ONLY) is strictly blocked by ROOT_READ_ONLY
        ToolResult writeDocs = operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.write", "1.0", Map.of("path", "docs:guide.md", "content", "hacked")));
        assertThat(writeDocs.successful()).isFalse();
        assertThat(writeDocs.structuredData())
                .containsEntry("errorCode", "ROOT_READ_ONLY")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_WRITE_PERMISSION");

        // 3. Delete from docs root (READ_ONLY) is strictly blocked by ROOT_READ_ONLY
        ToolResult deleteDocs = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.delete", "1.0", Map.of("path", "docs:guide.md")));
        assertThat(deleteDocs.successful()).isFalse();
        assertThat(deleteDocs.structuredData()).containsEntry("errorCode", "ROOT_READ_ONLY");

        // 4. Write to config root (READ_WRITE) succeeds
        ToolResult writeConfig = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "config:extra.yml", "content", "mode: dev")));
        assertThat(writeConfig.successful()).isTrue();
        assertThat(writeConfig.structuredData()).containsEntry("path", "extra.yml");
    }

    @Test
    void e2e02_pathEscapeAndTraversalDefense() {
        // Escaping root via relative traversal
        ToolResult escaped = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.read", "1.0", Map.of("path", "docs:../../etc/passwd")));
        assertThat(escaped.successful()).isFalse();
        assertThat(escaped.structuredData()).containsEntry("errorCode", "PATH_ESCAPE_FORBIDDEN");
    }

    @Test
    void e2e03_protectedDirectPhysicalDeletionAndDirectoryDefense() throws Exception {
        Files.writeString(mainDir.resolve("temp.txt"), "delete me", StandardCharsets.UTF_8);
        Files.createDirectories(mainDir.resolve("folder"));

        // 1. Regular file direct physical deletion
        ToolResult deleteFile = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.delete", "1.0", Map.of("path", "temp.txt")));
        assertThat(deleteFile.successful()).isTrue();
        assertThat(deleteFile.structuredData())
                .containsEntry("path", "temp.txt")
                .doesNotContainKeys("quarantineToken", "changeSetId", "changeReviewArtifact");
        assertThat(Files.exists(mainDir.resolve("temp.txt"))).isFalse();

        // 2. Directory deletion is strictly rejected
        ToolResult deleteDir = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.delete", "1.0", Map.of("path", "folder")));
        assertThat(deleteDir.successful()).isFalse();
        assertThat(Files.exists(mainDir.resolve("folder"))).isTrue();
    }

    @Test
    void e2e04_sessionChangeLedgerCompactingAndCancellation() {
        // 1. Overwriting a file merges in ledger
        operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "note.txt", "content", "version 1")));
        operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.write", "1.0", Map.of("path", "note.txt", "content", "version 2")));

        List<SessionFileChangeRecord> mainChanges = ledger.compactedChanges(WorkspaceRootAlias.MAIN);
        assertThat(mainChanges).hasSize(1);
        assertThat(mainChanges.get(0).type()).isEqualTo(FileChangeType.CREATE);

        // 2. Created then deleted in same session cancels out
        operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "ephemeral.txt", "content", "temp")));
        operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.delete", "1.0", Map.of("path", "ephemeral.txt")));

        List<SessionFileChangeRecord> afterDelete = ledger.compactedChanges(WorkspaceRootAlias.MAIN);
        assertThat(afterDelete).hasSize(1);
        assertThat(afterDelete.get(0).path().value()).isEqualTo("note.txt");
    }

    @Test
    void e2e05_onDemandChangeReviewArtifactGeneration() {
        // Mutate across multiple roots
        operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "Service.java", "content", "class Service {}")));
        operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "config:db.yml", "content", "db: postgres")));

        Optional<CodingChangeReviewArtifact> artifactOpt = onDemandReview.generateReview(
                "run-e2e",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                "sha256:1111111111111111111111111111111111111111111111111111111111111111");

        assertThat(artifactOpt).isPresent();
        CodingChangeReviewArtifact artifact = artifactOpt.get();
        assertThat(artifact.totalFileCount()).isEqualTo(2);
        assertThat(artifact.fileSummaries())
                .extracting(CodingChangeReviewArtifact.FileSummary::path)
                .containsExactlyInAnyOrder("Service.java", "config:db.yml");
    }
}
