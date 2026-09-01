package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
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
        var changeSets = new InMemoryFileChangeSetStore();

        operations = new LocalFileToolOperations(
                workspaces,
                files,
                testMutations(workspaces, workspaceId, changeSets, new ProjectId("proj-multiroot")),
                () -> "id-1",
                changeSets,
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
                .containsEntry("path", "app.yml")
                .doesNotContainKeys("changeSetId", "quarantineToken", "changeReviewArtifact");
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
            InMemoryWorkspaceStore workspaces,
            WorkspaceId workspaceId,
            InMemoryFileChangeSetStore changeSets,
            ProjectId projectId) {
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
                FileChangeSet changeSet = FileChangeSet.pending(
                                new FileChangeSetId("change-set-test"),
                                projectId,
                                workspaceId,
                                request.context().operationId(),
                                request.context().runRef(),
                                request.context().toolCallRef(),
                                before,
                                request.context().actor(),
                                request.context().securityDecisionRef(),
                                Instant.parse("2026-08-05T00:00:00Z"))
                        .applied(after, List.of(change), true, Instant.parse("2026-08-05T00:00:01Z"));
                changeSets.create(changeSet);
                return new MutationResult(
                        new FileChangeSetId("change-set-test"),
                        FileChangeSetStatus.APPLIED,
                        before,
                        after,
                        List.of(change),
                        true,
                        false);
            }

            @Override
            public MutationResult write(WriteFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:write-result");
                return new MutationResult(
                        new FileChangeSetId("change-set-test"),
                        FileChangeSetStatus.APPLIED,
                        before,
                        after,
                        List.of(),
                        true,
                        false);
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:del-result");
                return new MutationResult(
                        new FileChangeSetId("change-set-test"),
                        FileChangeSetStatus.APPLIED,
                        before,
                        after,
                        List.of(),
                        true,
                        false);
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                WorkspaceRevision before =
                        workspaces.find(workspaceId).orElseThrow().revision();
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:move-result");
                return new MutationResult(
                        new FileChangeSetId("change-set-test"),
                        FileChangeSetStatus.APPLIED,
                        before,
                        after,
                        List.of(),
                        true,
                        false);
            }
        };
    }
}
