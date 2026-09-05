package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceMutationService;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.InMemoryProjectStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.project.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LocalFileToolOperationsMultiRootTest {

    @TempDir
    Path tempDir;

    private Path workspaceDir;
    private Path docsDir;
    private Path configDir;
    private LocalFileToolOperations operations;
    private WorkspaceId workspaceId;
    private InMemoryWorkspaceStore workspaces;
    private InMemoryWorkspaceBindingStore bindings;
    private HostWorkspaceLocationStore locations;
    private InMemorySessionChangeLedger ledger;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = tempDir.toRealPath();
        workspaceDir = tempDir.resolve("workspace-repo");
        docsDir = tempDir.resolve("docs-repo");
        configDir = tempDir.resolve("config-repo");
        Files.createDirectories(workspaceDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(configDir);

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        workspaceId = new WorkspaceId("ws-multiroot");
        bindings = new InMemoryWorkspaceBindingStore();
        workspaces = new InMemoryWorkspaceStore();
        locations = new HostWorkspaceLocationStore();
        ProjectId projectId = new ProjectId("proj-multiroot");
        PrincipalRef owner = new PrincipalRef("owner", "user");
        registerWorkspace(workspaceId, workspaceDir, HostDirectoryPermission.READ_WRITE, WorkspacePurpose.PRIMARY, now);
        WorkspaceId docsWorkspaceId = new WorkspaceId("ws-docs");
        registerWorkspace(docsWorkspaceId, docsDir, HostDirectoryPermission.READ_ONLY, WorkspacePurpose.DIRECTORY, now);
        WorkspaceId configWorkspaceId = new WorkspaceId("ws-config");
        registerWorkspace(
                configWorkspaceId, configDir, HostDirectoryPermission.READ_WRITE, WorkspacePurpose.DIRECTORY, now);

        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        new TenantRef("local"),
                        owner,
                        "multi-root-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        now,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, now));
        var idSequence = new AtomicInteger();
        var identifiers =
                (io.haifa.agent.common.id.IdentifierGenerator) () -> "multi-root-test-" + idSequence.incrementAndGet();
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, identifiers, () -> now);
        var scope = new HostWorkspaceScope(
                List.of(
                        AuthorizedHostDirectory.of(
                                workspaceId, workspaceDir.toRealPath(), HostDirectoryPermission.READ_WRITE),
                        AuthorizedHostDirectory.of(
                                docsWorkspaceId, docsDir.toRealPath(), HostDirectoryPermission.READ_ONLY),
                        AuthorizedHostDirectory.of(
                                configWorkspaceId, configDir.toRealPath(), HostDirectoryPermission.READ_WRITE)),
                1L);
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId, workspaces, bindings, locations, workspaceService, owner, () -> now, scope);

        var files = new HostWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        ledger = new InMemorySessionChangeLedger();

        operations =
                new LocalFileToolOperations(
                        workspaces, files, mutations, identifiers, () -> now, provisioning, ledger, null, true);
    }

    private void registerWorkspace(
            WorkspaceId id, Path directory, HostDirectoryPermission permission, WorkspacePurpose purpose, Instant now)
            throws IOException {
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("loc-" + id.value());
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-" + id.value());
        Path realPath = directory.toRealPath();
        locations.register(locationRef, realPath);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        permission.canWrite()
                                ? WorkspaceCapabilitySet.readWriteFiles()
                                : WorkspaceCapabilitySet.readOnlyFiles(),
                        permission.canWrite() ? WorkspacePermissionSet.readWrite() : WorkspacePermissionSet.readOnly(),
                        HostWorkspaceLocationStore.fingerprintFor(realPath),
                        now)
                .activate(now);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        id,
                        new ProjectId("proj-multiroot"),
                        purpose,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));
    }

    @Test
    void readsFileWithHostAbsolutePath() throws IOException {
        Files.writeString(workspaceDir.resolve("App.java"), "public class App {}", StandardCharsets.UTF_8);
        String hostPath =
                workspaceDir.resolve("App.java").toAbsolutePath().normalize().toString();

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
    void listReadAndSearchReturnHostAbsolutePaths() throws IOException {
        Path file = workspaceDir.resolve("absolute-result.txt");
        Files.writeString(file, "search needle", StandardCharsets.UTF_8);
        String rootPath = workspaceDir.toAbsolutePath().normalize().toString();
        String filePath = file.toAbsolutePath().normalize().toString();

        var listed = operations.execute(
                "file.list",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", rootPath)));
        var read = operations.execute(
                "file.read",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", filePath)));
        var searched = operations.execute(
                "file.search",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", rootPath, "query", "needle")));

        assertThat(listed.successful()).isTrue();
        assertThat(resultPaths(listed.structuredData().get("entries"))).contains(filePath);
        assertThat(read.structuredData()).containsEntry("path", filePath);
        assertThat(resultPaths(searched.structuredData().get("results"))).containsExactly(filePath);
    }

    @Test
    void compactsCreateReplaceMoveAndDeleteWithinOneLogicalWorkspace() {
        String created =
                workspaceDir.resolve("created.txt").toAbsolutePath().normalize().toString();
        String moved =
                workspaceDir.resolve("moved.txt").toAbsolutePath().normalize().toString();

        assertThat(execute("file.create", Map.of("path", created, "content", "one"))
                        .successful())
                .isTrue();
        assertThat(execute("file.write", Map.of("path", created, "content", "two"))
                        .successful())
                .isTrue();
        assertThat(ledger.compactedChanges(workspaceId)).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(FileChangeType.CREATE);
            assertThat(change.path().projectPath()).isEqualTo(ProjectPath.of("created.txt"));
        });

        assertThat(execute("file.move", Map.of("source", created, "destination", moved))
                        .successful())
                .isTrue();
        assertThat(ledger.compactedChanges(workspaceId)).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(FileChangeType.CREATE);
            assertThat(change.path().projectPath()).isEqualTo(ProjectPath.of("moved.txt"));
        });

        assertThat(execute("file.delete", Map.of("path", moved)).successful()).isTrue();
        assertThat(ledger.compactedChanges(workspaceId)).isEmpty();
    }

    @Test
    void sameRelativePathInTwoAuthorizedDirectoriesHasDistinctLogicalIdentity() {
        String mainPath =
                workspaceDir.resolve("same.txt").toAbsolutePath().normalize().toString();
        String configPath =
                configDir.resolve("same.txt").toAbsolutePath().normalize().toString();

        assertThat(execute("file.create", Map.of("path", mainPath, "content", "workspace"))
                        .successful())
                .isTrue();
        assertThat(execute("file.create", Map.of("path", configPath, "content", "config"))
                        .successful())
                .isTrue();

        assertThat(ledger.allCompactedChanges()).hasSize(2);
        assertThat(ledger.allCompactedChanges().values().stream()
                        .flatMap(List::stream)
                        .toList())
                .allSatisfy(change -> assertThat(change.path().projectPath()).isEqualTo(ProjectPath.of("same.txt")))
                .extracting(change -> change.path().workspaceId())
                .containsExactlyInAnyOrder(workspaceId, new WorkspaceId("ws-config"));
    }

    private ToolResult execute(String toolName, Map<String, Object> values) {
        return operations.execute(
                toolName,
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-ledger",
                "policy-1",
                arguments(values));
    }

    private static List<Object> resultPaths(Object entries) {
        return ((List<?>) entries)
                .stream().map(entry -> (Object) ((Map<?, ?>) entry).get("path")).toList();
    }

    @Test
    void readsFromAuthorizedReadOnlyDirectory() throws IOException {
        Files.writeString(docsDir.resolve("guide.md"), "# Guide", StandardCharsets.UTF_8);
        String docPath =
                docsDir.resolve("guide.md").toAbsolutePath().normalize().toString();
        var res = operations.execute(
                "file.stat",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", docPath)));
        assertThat(res.successful()).isTrue();
        assertThat(res.structuredData()).containsEntry("path", docPath);
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
    void writableDirectoriesCreateMissingTargetsThroughFileWrite(String targetDirectory) throws IOException {
        Path target = "workspace".equals(targetDirectory)
                ? workspaceDir.resolve("contract.txt")
                : configDir.resolve("contract.txt");
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
    void writableDirectoriesShareCreateWriteAndDeleteContract(String targetDirectory) throws IOException {
        Path target = "workspace".equals(targetDirectory)
                ? workspaceDir.resolve("contract.txt")
                : configDir.resolve("contract.txt");
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
        return Stream.of("workspace", "config");
    }

    @Test
    void attachesUserApprovedDirectoryForThisAgent() throws IOException {
        Path extraDir = tempDir.resolve("extra-repo");
        Files.createDirectories(extraDir);

        var authorization = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", extraDir.toString(), "permission", "read-write")));
        String notePath =
                extraDir.resolve("note.txt").toAbsolutePath().normalize().toString();
        var created = operations.execute(
                "file.create",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", notePath, "content", "authorized")));

        assertThat(authorization.successful()).isTrue();
        assertThat(authorization.structuredData()).containsEntry("permission", "READ_WRITE");
        assertThat(created.successful()).isTrue();
        assertThat(Files.readString(extraDir.resolve("note.txt"))).isEqualTo("authorized");
    }

    @Test
    void usesAuthorizedDirectoryWorkspaceRevisionWhenWriting() throws IOException {
        Path extraDir = tempDir.resolve("authorized-revision-repo");
        Files.createDirectories(extraDir);

        var authorization = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", extraDir.toString(), "permission", "read-write")));
        assertThat(authorization.successful()).isTrue();

        WorkspaceId extraWsId = new WorkspaceId(
                authorization.structuredData().get("workspaceId").toString());
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        Workspace before = workspaces.find(extraWsId).orElseThrow();
        Workspace customRevision = before.advanceRevision(
                new WorkspaceRevision(before.revision().sequence() + 1, "sha256:custom-extra-rev"), now);
        workspaces.save(customRevision, before.version());

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
        assertThat(workspaces.find(extraWsId).orElseThrow().revision().sequence())
                .isGreaterThan(customRevision.revision().sequence());
    }

    @Test
    void failsClosedIfScopeVersionChangedBetweenResolutionAndIo() throws Exception {
        Path extraDir = tempDir.resolve("toctou-extra");
        Files.createDirectories(extraDir);

        HostWorkspaceScope initialScope = operations.currentScope();
        Path file = workspaceDir.resolve("toctou.txt");
        var resolvedTarget =
                initialScope.resolve(file.toAbsolutePath().normalize().toString());
        assertThat(initialScope.version()).isEqualTo(1L);

        var authorization = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", extraDir.toString(), "permission", "read-write")));
        assertThat(authorization.successful()).isTrue();
        assertThat(operations.currentScope().version()).isGreaterThan(initialScope.version());
        assertThat(initialScope.version())
                .isNotEqualTo(operations.currentScope().version());
    }

    @Test
    void reauthorizingTheDefaultDirectoryDoesNotDuplicateItsBoundary() {
        int originalDirectoryCount =
                operations.currentScope().allowedDirectories().size();
        var result = operations.execute(
                "workspace.attach",
                workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", workspaceDir.toString(), "permission", "read-only")));

        assertThat(result.successful()).isTrue();
        assertThat(operations.currentScope().allowedDirectories()).hasSize(originalDirectoryCount);
    }

    @Test
    void rejectsCrossRootMoveWithoutChangingEitherDirectory() throws IOException {
        Files.writeString(configDir.resolve("move.txt"), "source", StandardCharsets.UTF_8);

        String srcPath =
                configDir.resolve("move.txt").toAbsolutePath().normalize().toString();
        String dstPath =
                workspaceDir.resolve("moved.txt").toAbsolutePath().normalize().toString();
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
        assertThat(Files.exists(workspaceDir.resolve("moved.txt"))).isFalse();
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
                workspaceDir.resolve("second.txt").toAbsolutePath().normalize().toString();
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
        assertThat(workspaceDir.resolve("second.txt")).doesNotExist();
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
                .containsEntry("errorCode", "PATH_DENIED")
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
    void reportsPathNotFoundForAnAbsentAuthorizedDirectoryDeleteTarget() {
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
                .containsEntry("failureCategory", "WORKSPACE_SCOPE_DENIED")
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
}
