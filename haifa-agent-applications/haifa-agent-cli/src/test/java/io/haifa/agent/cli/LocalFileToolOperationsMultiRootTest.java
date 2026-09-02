package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
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

class LocalFileToolOperationsMultiRootTest {

    @TempDir
    Path tempDir;

    private Path mainDir;
    private Path docsDir;
    private Path configDir;
    private LocalWorkspaceRootRegistry registry;
    private LocalFileToolOperations operations;
    private WorkspaceId workspaceId;

    @BeforeEach
    void setUp() throws IOException {
        mainDir = tempDir.resolve("main-repo");
        docsDir = tempDir.resolve("docs-repo");
        configDir = tempDir.resolve("config-repo");
        Files.createDirectories(mainDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(configDir);

        registry = LocalWorkspaceRootRegistry.builder()
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.MAIN,
                        mainDir,
                        WorkspaceRootPermission.READ_WRITE,
                        WorkspaceRootStrategy.GIT,
                        false))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("docs"),
                        docsDir,
                        WorkspaceRootPermission.READ_ONLY,
                        WorkspaceRootStrategy.PLAIN,
                        false))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("config"),
                        configDir,
                        WorkspaceRootPermission.READ_WRITE,
                        WorkspaceRootStrategy.PLAIN,
                        false))
                .build();

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        workspaceId = new WorkspaceId("ws-multiroot");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-multiroot");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("loc-multiroot");

        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, mainDir);

        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        LocalWorkspaceLocationStore.fingerprintFor(mainDir),
                        now)
                .activate(now);
        bindings.create(binding);

        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("proj-multiroot"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));

        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());

        operations = new LocalFileToolOperations(
                workspaces,
                files,
                testMutations(workspaces, workspaceId, new ProjectId("proj-multiroot")),
                () -> "id-1",
                registry);
    }

    @Test
    void readsFileWithMainPrefixAndImplicitMain() throws IOException {
        Files.writeString(mainDir.resolve("App.java"), "public class App {}", StandardCharsets.UTF_8);

        var resExplicit = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "main:App.java")));
        assertThat(resExplicit.successful()).isTrue();
        assertThat(resExplicit.structuredData()).containsEntry("content", "public class App {}");

        var resImplicit = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "App.java")));
        assertThat(resImplicit.successful()).isTrue();
        assertThat(resImplicit.structuredData()).containsEntry("content", "public class App {}");
    }

    @Test
    void readsFromAttachedReadOnlyDirectory() {
        var res = operations.execute(
                "file.stat",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "docs:guide.md")));
        // Handled by root resolver without ROOT_ALIAS_NOT_FOUND or ROOT_READ_ONLY
        assertThat(res.successful()).isFalse(); // Target file does not exist on disk in test fixture
        assertThat(res.structuredData()).containsEntry("errorCode", "PATH_NOT_FOUND");
    }

    @Test
    void deniesWriteToReadOnlyRoot() {
        var createRes = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "docs:guide.md", "content", "# Guide")));
        assertThat(createRes.successful()).isFalse();
        assertThat(createRes.structuredData())
                .containsEntry("errorCode", "ROOT_READ_ONLY")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_WRITE_PERMISSION");

        var writeRes = operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "docs:guide.md", "content", "# Updated")));
        assertThat(writeRes.successful()).isFalse();
        assertThat(writeRes.structuredData()).containsEntry("errorCode", "ROOT_READ_ONLY");

        var deleteRes = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "docs:guide.md")));
        assertThat(deleteRes.successful()).isFalse();
        assertThat(deleteRes.structuredData()).containsEntry("errorCode", "ROOT_READ_ONLY");
    }

    @Test
    void allowsWriteToReadWriteAttachedRoot() {
        var createRes = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "config:app.yml", "content", "env: prod")));
        assertThat(createRes.successful()).isTrue();
        assertThat(createRes.structuredData())
                .containsEntry("path", "config:app.yml")
                .doesNotContainKeys("changeSetId", "quarantineToken", "changeReviewArtifact");
    }

    @Test
    void attachesUserApprovedDirectoryForThisAgentAndUsesItsAlias() throws IOException {
        Path extraDir = tempDir.resolve("extra-repo");
        Files.createDirectories(extraDir);

        var attached = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "alias", "extra",
                        "path", extraDir.toString(),
                        "permission", "read-write")));
        var created = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "extra:note.txt", "content", "attached")));

        assertThat(attached.successful()).isTrue();
        assertThat(attached.structuredData()).containsEntry("alias", "extra").containsEntry("permission", "READ_WRITE");
        assertThat(registry.find(WorkspaceRootAlias.of("extra"))).isPresent();
        assertThat(created.successful()).isTrue();
        assertThat(Files.readString(extraDir.resolve("note.txt"))).isEqualTo("attached");
    }

    @Test
    void rejectsAttachmentThatOverlapsTheMainDirectory() {
        var result = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "alias", "duplicate",
                        "path", mainDir.toString(),
                        "permission", "read-only")));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(registry.find(WorkspaceRootAlias.of("duplicate"))).isEmpty();
    }

    @Test
    void rejectsCrossRootMoveWithoutChangingEitherDirectory() throws IOException {
        Files.writeString(configDir.resolve("move.txt"), "source", StandardCharsets.UTF_8);

        var result = operations.execute(
                "file.move",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("source", "config:move.txt", "destination", "main:moved.txt")));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(Files.readString(configDir.resolve("move.txt"))).isEqualTo("source");
        assertThat(Files.exists(mainDir.resolve("moved.txt"))).isFalse();
    }

    @Test
    void rejectsDeletePatchBeforeChangingTheTargetFile() throws IOException {
        Files.writeString(configDir.resolve("keep.yml"), "keep: true\n", StandardCharsets.UTF_8);
        String patch =
                """
                *** Begin Patch
                *** Delete File: config:keep.yml
                *** End Patch
                """;

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(Files.readString(configDir.resolve("keep.yml"))).isEqualTo("keep: true\n");
    }

    @Test
    void appliesMultiFilePatchInOneToolCall() throws IOException {
        String patch =
                """
                *** Begin Patch
                *** Add File: config:first.txt
                +first
                *** Add File: config:second.txt
                +second
                *** End Patch
                """;

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData()).containsEntry("complete", true);
        assertThat(Files.readString(configDir.resolve("first.txt"))).isEqualTo("first\n");
        assertThat(Files.readString(configDir.resolve("second.txt"))).isEqualTo("second\n");
    }

    @Test
    void deletesAttachedDirectoryOnlyWhenRecursiveIsExplicit() throws IOException {
        Path generated = configDir.resolve("generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("artifact.txt"), "temporary");

        var rejected = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "config:generated", "recursive", false)));

        assertThat(rejected.successful()).isFalse();
        assertThat(generated).exists();

        var deleted = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "config:generated", "recursive", true)));

        assertThat(deleted.successful()).isTrue();
        assertThat(generated).doesNotExist();
    }

    @Test
    void rejectsUnregisteredRootAlias() {
        var res = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "unregistered:data.csv")));
        assertThat(res.successful()).isFalse();
        assertThat(res.structuredData())
                .containsEntry("errorCode", "ROOT_ALIAS_NOT_FOUND")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_REGISTERED_ROOT_ALIAS");
    }

    @Test
    void rejectsHostAbsolutePaths() {
        var resWindows = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "D:/workspace/secret.txt")));
        assertThat(resWindows.successful()).isFalse();
        assertThat(resWindows.structuredData())
                .containsEntry("errorCode", "ABSOLUTE_PATH_FORBIDDEN")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ALIAS_RELATIVE_SYNTAX");

        var resPosix = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "/etc/shadow")));
        assertThat(resPosix.successful()).isFalse();
        assertThat(resPosix.structuredData()).containsEntry("errorCode", "ABSOLUTE_PATH_FORBIDDEN");
    }

    @Test
    void rejectsDirectoryTraversalEscape() {
        var res = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "main:../../etc/passwd")));
        assertThat(res.successful()).isFalse();
        assertThat(res.structuredData())
                .containsEntry("errorCode", "PATH_ESCAPE_FORBIDDEN")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "USE_BOUNDED_PATH");
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.test", "1.1.0", values);
    }

    private WorkspaceMutationProvider testMutations(
            InMemoryWorkspaceStore workspaces, WorkspaceId workspaceId, ProjectId projectId) {
        return new WorkspaceMutationProvider() {
            @Override
            public String providerId() {
                return "test-mutations";
            }

            @Override
            public WorkspaceMutationCapabilities capabilities() {
                return new WorkspaceMutationCapabilities(false, false, "test");
            }

            @Override
            public MutationResult create(CreateFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:create-result");
                FileChange change = new FileChange(
                        FileChangeType.CREATE,
                        request.path().projectPath(),
                        null,
                        null,
                        new FileVersion(FileType.FILE, request.content().length, "sha256:hash-val"));

                return new MutationResult(before, after, List.of(change), true, false);
            }

            @Override
            public MutationResult write(WriteFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:write-result");
                return new MutationResult(before, after, List.of(), true, false);
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:del-result");
                return new MutationResult(before, after, List.of(), true, false);
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:move-result");
                return new MutationResult(before, after, List.of(), true, false);
            }
        };
    }
}
