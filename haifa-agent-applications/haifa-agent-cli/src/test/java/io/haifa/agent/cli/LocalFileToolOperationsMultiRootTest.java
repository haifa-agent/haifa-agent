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
import io.haifa.agent.project.mutation.MutationErrorCode;
import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationCapabilities;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WriteFileRequest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRoot;
import io.haifa.agent.project.provider.local.root.LocalWorkspaceRootRegistry;
import io.haifa.agent.project.provider.local.scope.LocalWorkspaceScope;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LocalFileToolOperationsMultiRootTest {

    @TempDir
    Path tempDir;

    private Path mainDir;
    private Path docsDir;
    private Path configDir;
    private LocalWorkspaceRootRegistry registry;
    private LocalFileToolOperations operations;
    private WorkspaceId workspaceId;
    private InMemoryWorkspaceStore workspaces;
    private InMemoryWorkspaceBindingStore bindings;
    private LocalWorkspaceLocationStore locations;

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
                        WorkspaceRootStrategy.GIT))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("docs"),
                        docsDir,
                        WorkspaceRootPermission.READ_ONLY,
                        WorkspaceRootStrategy.PLAIN))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("config"),
                        configDir,
                        WorkspaceRootPermission.READ_WRITE,
                        WorkspaceRootStrategy.PLAIN))
                .build();

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        workspaceId = new WorkspaceId("ws-multiroot");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-multiroot");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("loc-multiroot");

        bindings = new InMemoryWorkspaceBindingStore();
        workspaces = new InMemoryWorkspaceStore();
        locations = new LocalWorkspaceLocationStore();
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
                workspaces, files, testMutations(workspaces, workspaceId), () -> "id-1", registry);
    }

    @Test
    void readsFileWithHostAbsolutePath() throws IOException {
        Files.writeString(mainDir.resolve("App.java"), "public class App {}", StandardCharsets.UTF_8);
        String hostPath =
                mainDir.resolve("App.java").toAbsolutePath().normalize().toString();

        var res = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath)));
        assertThat(res.successful()).isTrue();
        assertThat(res.structuredData()).containsEntry("content", "public class App {}");

        var resImplicit = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "App.java")));
        assertThat(resImplicit.successful()).isFalse();
        assertThat(resImplicit.structuredData()).containsEntry("errorCode", "INVALID_ARGUMENT");

        var resExplicit = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "main:App.java")));
        assertThat(resExplicit.successful()).isFalse();
        assertThat(resExplicit.structuredData()).containsEntry("errorCode", "INVALID_ARGUMENT");
    }

    @Test
    void readsFromAttachedReadOnlyDirectory() {
        String docPath =
                docsDir.resolve("guide.md").toAbsolutePath().normalize().toString();
        var res = operations.execute(
                "file.stat",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", docPath)));
        assertThat(res.successful()).isFalse();
        assertThat(res.structuredData()).containsEntry("errorCode", "PATH_NOT_FOUND");
    }

    @Test
    void deniesWriteToReadOnlyRoot() {
        String docPath =
                docsDir.resolve("guide.md").toAbsolutePath().normalize().toString();
        var createRes = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", docPath, "content", "# Guide")));
        assertThat(createRes.successful()).isFalse();
        assertThat(createRes.structuredData())
                .containsEntry("errorCode", "PERMISSION_DENIED")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_WRITE_PERMISSION");

        var writeRes = operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", docPath, "content", "# Updated")));
        assertThat(writeRes.successful()).isFalse();
        assertThat(writeRes.structuredData()).containsEntry("errorCode", "PERMISSION_DENIED");

        var deleteRes = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", docPath)));
        assertThat(deleteRes.successful()).isFalse();
        assertThat(deleteRes.structuredData()).containsEntry("errorCode", "PERMISSION_DENIED");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writableFileRoots")
    void writableRootsCreateMissingTargetsThroughFileWrite(String targetRoot) throws IOException {
        Path target = "main".equals(targetRoot) ? mainDir.resolve("contract.txt") : configDir.resolve("contract.txt");
        String path = target.toAbsolutePath().normalize().toString();
        var written = operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", path, "content", "created by write")));

        assertThat(written.successful()).isTrue();
        assertThat(written.summary()).isEqualTo("Created " + path);
        assertThat(written.structuredData()).containsEntry("path", path);
        assertThat(Files.readString(target)).isEqualTo("created by write");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writableFileRoots")
    void writableRootsShareCreateWriteAndDeleteContract(String targetRoot) throws IOException {
        Path target = "main".equals(targetRoot) ? mainDir.resolve("contract.txt") : configDir.resolve("contract.txt");
        String path = target.toAbsolutePath().normalize().toString();
        var created = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", path, "content", "first")));
        assertThat(created.successful()).isTrue();
        assertThat(created.structuredData()).containsEntry("path", path);
        assertThat(Files.readString(target)).isEqualTo("first");

        var written = operations.execute(
                "file.write",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", path, "content", "second")));
        assertThat(written.successful()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("second");

        var deleted = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", path)));
        assertThat(deleted.successful()).isTrue();
        assertThat(target).doesNotExist();
    }

    private static Stream<String> writableFileRoots() {
        return Stream.of("main", "config");
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
        String notePath =
                extraDir.resolve("note.txt").toAbsolutePath().normalize().toString();
        var created = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", notePath, "content", "attached")));

        assertThat(attached.successful()).isTrue();
        assertThat(attached.structuredData()).containsEntry("permission", "READ_WRITE");
        assertThat(created.successful()).isTrue();
        assertThat(Files.readString(extraDir.resolve("note.txt"))).isEqualTo("attached");
    }

    @Test
    void usesAttachedWorkspaceRevisionWhenWritingToAttachedRoot() throws IOException {
        Path extraDir = tempDir.resolve("attached-rev-repo");
        Files.createDirectories(extraDir);

        var attached = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "alias", "attached-rev",
                        "path", extraDir.toString(),
                        "permission", "read-write")));
        assertThat(attached.successful()).isTrue();

        WorkspaceId extraWsId =
                new WorkspaceId(attached.structuredData().get("workspaceId").toString());
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceRevision extraRevision = new WorkspaceRevision(999L, "sha256:custom-extra-rev");
        WorkspaceLocationRef extraLoc = new WorkspaceLocationRef("loc-extra");
        locations.register(extraLoc, extraDir);
        WorkspaceBindingId extraBindingId = new WorkspaceBindingId("binding-extra");
        WorkspaceBinding extraBinding = WorkspaceBinding.provision(
                        extraBindingId,
                        extraLoc,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        LocalWorkspaceLocationStore.fingerprintFor(extraDir),
                        now)
                .activate(now);
        bindings.create(extraBinding);

        workspaces.create(Workspace.provision(
                        extraWsId,
                        new ProjectId("proj-attached"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), extraBindingId, "test"),
                        extraRevision,
                        now)
                .activate(now));

        String notePath =
                extraDir.resolve("note.txt").toAbsolutePath().normalize().toString();
        var created = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", notePath, "content", "content in extra")));

        assertThat(created.successful()).isTrue();
        assertThat(Files.readString(extraDir.resolve("note.txt"))).isEqualTo("content in extra");
    }

    @Test
    void failsClosedIfScopeVersionChangedBetweenResolutionAndIo() throws Exception {
        Path extraDir = tempDir.resolve("toctou-extra");
        Files.createDirectories(extraDir);

        LocalWorkspaceScope initialScope = operations.currentScope();
        Path file = mainDir.resolve("toctou.txt");
        var resolvedTarget =
                initialScope.resolve(file.toAbsolutePath().normalize().toString());
        assertThat(initialScope.version()).isEqualTo(1L);

        var attached = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "alias", "toctou-extra",
                        "path", extraDir.toString(),
                        "permission", "read-write")));
        assertThat(attached.successful()).isTrue();
        assertThat(operations.currentScope().version()).isGreaterThan(initialScope.version());
        assertThat(initialScope.version())
                .isNotEqualTo(operations.currentScope().version());
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

        String srcPath =
                configDir.resolve("move.txt").toAbsolutePath().normalize().toString();
        String dstPath =
                mainDir.resolve("moved.txt").toAbsolutePath().normalize().toString();
        var result = operations.execute(
                "file.move",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("source", srcPath, "destination", dstPath)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "CROSS_DIRECTORY_MOVE")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_CREATE_AND_DELETE");
        assertThat(Files.readString(configDir.resolve("move.txt"))).isEqualTo("source");
        assertThat(Files.exists(mainDir.resolve("moved.txt"))).isFalse();
    }

    @Test
    void rejectsDeletePatchBeforeChangingTheTargetFile() throws IOException {
        Files.writeString(configDir.resolve("keep.yml"), "keep: true\n", StandardCharsets.UTF_8);
        String targetPath =
                configDir.resolve("keep.yml").toAbsolutePath().normalize().toString();
        String patch =
                """
                *** Begin Patch
                *** Delete File: %s
                *** End Patch
                """
                        .formatted(targetPath);

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
        String first =
                configDir.resolve("first.txt").toAbsolutePath().normalize().toString();
        String second =
                configDir.resolve("second.txt").toAbsolutePath().normalize().toString();
        String patch =
                """
                *** Begin Patch
                *** Add File: %s
                +first
                *** Add File: %s
                +second
                *** End Patch
                """
                        .formatted(first, second);

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("complete", true)
                .containsEntry("atomic", false)
                .containsEntry("appliedPaths", List.of(first, second));
        assertThat(Files.readString(configDir.resolve("first.txt"))).isEqualTo("first\n");
        assertThat(Files.readString(configDir.resolve("second.txt"))).isEqualTo("second\n");
    }

    @Test
    void rejectsCrossRootPatchBeforeChangingEitherRoot() {
        String first =
                configDir.resolve("first.txt").toAbsolutePath().normalize().toString();
        String second =
                mainDir.resolve("second.txt").toAbsolutePath().normalize().toString();
        String patch =
                """
                *** Begin Patch
                *** Add File: %s
                +first
                *** Add File: %s
                +second
                *** End Patch
                """
                        .formatted(first, second);

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "CROSS_ROOT_PATCH_FORBIDDEN")
                .containsEntry("complete", false)
                .containsEntry("appliedPaths", List.of());
        assertThat(configDir.resolve("first.txt")).doesNotExist();
        assertThat(mainDir.resolve("second.txt")).doesNotExist();
    }

    @Test
    void rejectsConflictingMultiFilePatchBeforeWritingAnyFile() throws IOException {
        Files.writeString(configDir.resolve("first.txt"), "first\n");
        Files.writeString(configDir.resolve("second.txt"), "second\n");
        String first =
                configDir.resolve("first.txt").toAbsolutePath().normalize().toString();
        String second =
                configDir.resolve("second.txt").toAbsolutePath().normalize().toString();
        String patch =
                """
                *** Begin Patch
                *** Update File: %s
                @@ first
                -first
                +FIRST
                *** Update File: %s
                @@ missing
                -missing
                +SECOND
                *** End Patch
                """
                        .formatted(first, second);

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "PATCH_CONFLICT")
                .containsEntry("complete", false)
                .containsEntry("appliedPaths", List.of())
                .containsEntry("reconciliationRequired", false);
        assertThat(Files.readString(configDir.resolve("first.txt"))).isEqualTo("first\n");
        assertThat(Files.readString(configDir.resolve("second.txt"))).isEqualTo("second\n");
    }

    @Test
    void reportsCommittedPrefixWhenAFileFailsDuringPatchCommit() throws IOException {
        Files.writeString(configDir.resolve("blocked"), "not a directory");
        String first =
                configDir.resolve("first.txt").toAbsolutePath().normalize().toString();
        String second = configDir
                .resolve("blocked")
                .resolve("second.txt")
                .toAbsolutePath()
                .normalize()
                .toString();
        String patch =
                """
                *** Begin Patch
                *** Add File: %s
                +first
                *** Add File: %s
                +second
                *** End Patch
                """
                        .formatted(first, second);

        var result = operations.execute(
                "file.patch",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("patch", patch)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("complete", false)
                .containsEntry("atomic", false)
                .containsEntry("appliedPaths", List.of(first))
                .containsEntry("failedPath", second)
                .containsEntry("errorCode", "IO_FAILURE")
                .containsEntry("reconciliationRequired", true);
        assertThat(Files.readString(configDir.resolve("first.txt"))).isEqualTo("first\n");
    }

    @Test
    void rejectsNonEmptyDirectoryDelete() throws Exception {
        Path generated = configDir.resolve("generated");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("temp.log"), "sample");

        var rejected = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", generated.toAbsolutePath().normalize().toString())));

        assertThat(rejected.successful()).isFalse();
        assertThat(rejected.structuredData()).containsEntry("errorCode", "PATH_DENIED");
        assertThat(generated).exists();
    }

    @Test
    void reportsPathNotFoundForAnAbsentAttachedDeleteTarget() {
        var result = operations.execute(
                "file.delete",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "path",
                        configDir
                                .resolve("missing.txt")
                                .toAbsolutePath()
                                .normalize()
                                .toString())));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "PATH_NOT_FOUND");
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
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");
    }

    @Test
    void rejectsUnauthorizedHostPaths() {
        Path outside = tempDir.resolveSibling("unauthorized-outside").resolve("secret.txt");
        String outsidePath = outside.toAbsolutePath().normalize().toString();
        var res = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", outsidePath)));
        assertThat(res.successful()).isFalse();
        assertThat(res.structuredData())
                .containsEntry("errorCode", "ACCESS_DENIED")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_DIRECTORY_AUTHORIZATION");
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
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.test", "1.1.0", values);
    }

    private WorkspaceMutationProvider testMutations(InMemoryWorkspaceStore workspaces, WorkspaceId workspaceId) {
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
                writeTargetFile(request.path(), request.content());
                WorkspaceRevision before = workspaces
                        .find(request.path().workspaceId())
                        .orElseThrow()
                        .revision();
                if (!request.precondition().expectedWorkspaceRevision().equals(before)) {
                    throw new WorkspaceMutationException(
                            MutationErrorCode.REVISION_CONFLICT,
                            request.path(),
                            "revision conflict: expected "
                                    + request.precondition().expectedWorkspaceRevision() + " but was " + before);
                }
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
                writeTargetFile(request.path(), request.content());
                WorkspaceRevision before = workspaces
                        .find(request.path().workspaceId())
                        .orElseThrow()
                        .revision();
                if (!request.precondition().expectedWorkspaceRevision().equals(before)) {
                    throw new WorkspaceMutationException(
                            MutationErrorCode.REVISION_CONFLICT,
                            request.path(),
                            "revision conflict: expected "
                                    + request.precondition().expectedWorkspaceRevision() + " but was " + before);
                }
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:write-result");
                return new MutationResult(before, after, List.of(), true, false);
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                try {
                    Files.deleteIfExists(
                            mainDir.resolve(request.path().projectPath().value()));
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
                WorkspaceRevision before = workspaces
                        .find(request.path().workspaceId())
                        .orElseThrow()
                        .revision();
                if (!request.precondition().expectedWorkspaceRevision().equals(before)) {
                    throw new WorkspaceMutationException(
                            MutationErrorCode.REVISION_CONFLICT,
                            request.path(),
                            "revision conflict: expected "
                                    + request.precondition().expectedWorkspaceRevision() + " but was " + before);
                }
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:del-result");
                return new MutationResult(before, after, List.of(), true, false);
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                try {
                    Path destination =
                            mainDir.resolve(request.destination().projectPath().value());
                    if (destination.getParent() != null) Files.createDirectories(destination.getParent());
                    Files.move(mainDir.resolve(request.source().projectPath().value()), destination);
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
                WorkspaceRevision before = workspaces
                        .find(request.source().workspaceId())
                        .orElseThrow()
                        .revision();
                if (!request.sourcePrecondition().expectedWorkspaceRevision().equals(before)) {
                    throw new WorkspaceMutationException(
                            MutationErrorCode.REVISION_CONFLICT,
                            request.source(),
                            "revision conflict: expected "
                                    + request.sourcePrecondition().expectedWorkspaceRevision() + " but was " + before);
                }
                WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:move-result");
                return new MutationResult(before, after, List.of(), true, false);
            }
        };
    }

    private void writeTargetFile(io.haifa.agent.project.path.WorkspacePath path, byte[] content) {
        try {
            Path dir = mainDir;
            if (workspaces != null && bindings != null && locations != null) {
                var wsOpt = workspaces.find(path.workspaceId());
                if (wsOpt.isPresent()) {
                    var bindingOpt = bindings.find(wsOpt.get().root().bindingId());
                    if (bindingOpt.isPresent()) {
                        dir = locations.resolveForTrustedProvider(
                                bindingOpt.get().locationRef());
                    }
                }
            }
            Path target = dir.resolve(path.projectPath().value());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
