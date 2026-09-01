package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.domain.ProjectId;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-End verification of Multi-Root Workspace Simplification:
 * Authoritative physical routing, strict permission isolation, anti-escape defense,
 * and explicit file-operation behavior.
 */
class MultiRootWorkspaceSimplificationE2ETest {

    @TempDir
    Path tempDir;

    private Path mainDir;
    private Path docsDir;
    private Path configDir;

    private WorkspaceId workspaceId;
    private LocalWorkspaceRootRegistry registry;
    private InMemorySessionChangeLedger ledger;
    private LocalFileToolOperations operations;

    @BeforeEach
    void setUp() throws IOException {
        mainDir = tempDir.resolve("main_project");
        docsDir = tempDir.resolve("docs_repo");
        configDir = tempDir.resolve("config_repo");

        Files.createDirectories(mainDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(configDir);

        // Populate initial files
        Files.writeString(mainDir.resolve("App.java"), "public class App {}", StandardCharsets.UTF_8);
        Files.writeString(docsDir.resolve("guide.md"), "# User Guide", StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve("app.yml"), "server:\n  port: 8080\n", StandardCharsets.UTF_8);

        // 1. Setup multi-root registry
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
                    Path target = mainDir.resolve(request.path().projectPath().toString());
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.write(target, request.content());
                    return new MutationResult(
                            WorkspaceRevision.initial("init"),
                            new WorkspaceRevision(1, "sha256:create"),
                            List.of(),
                            true,
                            false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public MutationResult write(WriteFileRequest request) {
                try {
                    Path target = mainDir.resolve(request.path().projectPath().toString());
                    Files.write(target, request.content());
                    return new MutationResult(
                            WorkspaceRevision.initial("init"),
                            new WorkspaceRevision(1, "sha256:write"),
                            List.of(),
                            true,
                            false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                try {
                    Path target = mainDir.resolve(request.path().projectPath().toString());
                    Files.deleteIfExists(target);
                    return new MutationResult(
                            WorkspaceRevision.initial("init"),
                            new WorkspaceRevision(1, "sha256:delete"),
                            List.of(),
                            true,
                            false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                try {
                    Path src = mainDir.resolve(request.source().projectPath().toString());
                    Path dst =
                            mainDir.resolve(request.destination().projectPath().toString());
                    if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                    Files.move(src, dst);
                    return new MutationResult(
                            WorkspaceRevision.initial("init"),
                            new WorkspaceRevision(1, "sha256:move"),
                            List.of(),
                            true,
                            false);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        operations = new LocalFileToolOperations(workspaces, files, mutationProvider, () -> "id-1", registry, ledger);
    }

    @Test
    void e2e01_multiRootAuthorizationAndReadOnlyPermissionIsolation() throws IOException {
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
        assertThat(writeDocs.structuredData()).containsEntry("errorCode", "ROOT_READ_ONLY");

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

        // 4. Write to config root (READ_WRITE) succeeds and physically modifies configDir, NOT mainDir
        ToolResult writeConfig = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "config:extra.yml", "content", "mode: dev")));
        assertThat(writeConfig.successful()).isTrue();
        assertThat(writeConfig.structuredData()).containsEntry("path", "config:extra.yml");

        // Physical disk assertions confirming real multi-root routing
        assertThat(Files.exists(configDir.resolve("extra.yml"))).isTrue();
        assertThat(Files.readString(configDir.resolve("extra.yml"), StandardCharsets.UTF_8))
                .isEqualTo("mode: dev");
        assertThat(Files.exists(mainDir.resolve("extra.yml"))).isFalse();
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
                "file.write",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.write", "1.0", Map.of("path", "config:app.yml", "content", "port: 9090")));
        operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.write", "1.0", Map.of("path", "config:app.yml", "content", "port: 9999")));

        List<SessionFileChangeRecord> configChanges = ledger.compactedChanges(WorkspaceRootAlias.of("config"));
        assertThat(configChanges).hasSize(1);
        assertThat(configChanges.getFirst().type()).isEqualTo(FileChangeType.REPLACE);
        assertThat(configChanges.getFirst().path().value()).isEqualTo("app.yml");

        // 2. Creating and deleting within same session cancels out
        operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.create", "1.0", Map.of("path", "config:ephemeral.txt", "content", "temp")));
        assertThat(ledger.compactedChanges(WorkspaceRootAlias.of("config"))).hasSize(2);

        operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.delete", "1.0", Map.of("path", "config:ephemeral.txt")));
        assertThat(ledger.compactedChanges(WorkspaceRootAlias.of("config"))).hasSize(1);
    }

    @Test
    void e2e06_multiRootPatchSupportAndReadOnlyProtection() throws IOException {
        // 1. Patch to read-only docs root is rejected
        String readOnlyPatch =
                """
                *** Begin Patch
                *** Update File: docs:guide.md
                @@ # User Guide
                -# User Guide
                +# Modified Guide
                *** End Patch
                """;
        ToolResult roPatchResult = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.patch", "1.0", Map.of("patch", readOnlyPatch)));
        assertThat(roPatchResult.successful()).isFalse();
        assertThat(roPatchResult.structuredData()).containsEntry("errorCode", "ROOT_READ_ONLY");

        // 2. Patch creating a new file in config root succeeds
        String createPatch =
                """
                *** Begin Patch
                *** Add File: config:patch-new.yml
                +feature:
                +  enabled: true
                *** End Patch
                """;
        ToolResult createPatchResult = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("op", "user"),
                "run-1",
                "p-1",
                new ToolArguments("file.patch", "1.0", Map.of("patch", createPatch)));
        assertThat(createPatchResult.successful()).isTrue();
        assertThat(Files.exists(configDir.resolve("patch-new.yml"))).isTrue();
        assertThat(Files.readString(configDir.resolve("patch-new.yml"), StandardCharsets.UTF_8))
                .contains("enabled: true");
    }
}
