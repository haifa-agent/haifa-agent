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
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
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
import io.haifa.agent.project.provider.local.scope.LocalAllowedDirectory;
import io.haifa.agent.project.provider.local.scope.LocalDirectoryPermission;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileToolOperationsTest {
    @TempDir
    Path root;

    @Test
    void continuesBoundedReadsWithOpaqueVersionedCursor() throws Exception {
        Path file = root.resolve("large.txt");
        Files.writeString(file, "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = file.toAbsolutePath().normalize().toString();
        var first = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath, "maxBytes", 8, "maxLines", 1)));
        String cursor = (String) first.structuredData().get("nextCursor");
        var second = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath, "cursor", cursor, "maxBytes", 8, "maxLines", 1)));

        assertThat(first.successful()).isTrue();
        assertThat(first.structuredData())
                .containsEntry("content", "one\n")
                .containsEntry("startLine", 1)
                .containsEntry("hasMore", true);
        assertThat(second.successful()).isTrue();
        assertThat(second.structuredData()).containsEntry("content", "two\n").containsEntry("startLine", 2);

        Files.writeString(file, "changed\ncontent\n", StandardCharsets.UTF_8);
        var stale = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath, "cursor", cursor)));
        assertThat(stale.successful()).isFalse();
        assertThat(stale.structuredData())
                .containsEntry("errorCode", "FILE_CURSOR_STALE")
                .containsEntry("failureActionCode", "RESTART_READ_FROM_CURRENT_VERSION")
                .containsEntry("retryable", true)
                .containsEntry("maximumAutomaticRetries", 1);
    }

    @Test
    void appliesModelVisibleContextPatchWithoutSeparatePathArgument() throws Exception {
        Path sourceFile = root.resolve("source.txt");
        Files.writeString(sourceFile, "anchor\nold\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@ anchor
                        -old
                        +new
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("complete", true)
                .doesNotContainKeys("changeReviewArtifactRef", "artifactRef", "changeReviewArtifact", "changeSetIds");
        assertThat(Files.readString(sourceFile)).isEqualTo("anchor\nnew\n");
    }

    @Test
    void returnsKnownToolFailureWhenWorkspaceMutationIsRejected() {
        Fixture fixture = fixture();
        String hostPath =
                root.resolve("existing.txt").toAbsolutePath().normalize().toString();

        var result = fixture.operations.execute(
                "file.create",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath, "content", "replacement")));

        assertThat(result.successful()).isFalse();
        assertThat(result.summary()).isEqualTo("Workspace mutation failed: TARGET_EXISTS (path=existing.txt)");
        assertThat(result.structuredData())
                .containsEntry("errorCode", "TARGET_EXISTS")
                .containsEntry("failureActionCode", "USE_FILE_WRITE_OR_PATCH")
                .containsEntry("retryable", false)
                .containsEntry("path", "existing.txt");
    }

    @Test
    void writesMissingTargetAndKeepsSensitivePathRecoveryActions() throws Exception {
        Fixture fixture = fixture();
        String missingPath =
                root.resolve("missing.txt").toAbsolutePath().normalize().toString();
        String sensitivePath = root.resolve(".env").toAbsolutePath().normalize().toString();

        var missingWrite = fixture.operations.execute(
                "file.write",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", missingPath, "content", "new")));
        var sensitiveRead = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", sensitivePath)));

        assertThat(missingWrite.successful()).isTrue();
        assertThat(missingWrite.summary()).isEqualTo("Created " + missingPath);
        assertThat(missingWrite.structuredData()).containsEntry("path", missingPath);
        assertThat(Files.readString(root.resolve("missing.txt"))).isEqualTo("new");
        assertThat(sensitiveRead.structuredData())
                .containsEntry("stableFailureCode", "SENSITIVE_PATH")
                .containsEntry("failureActionCode", "USER_ACTION_REQUIRED")
                .containsEntry("retryable", false)
                .containsEntry("maximumAutomaticRetries", 0);
        assertThat(sensitiveRead.structuredData().get("failureAction").toString())
                .contains("do not rename, relocate, or copy");
    }

    @Test
    void bindsFileMutationToTheToolCallAndWritesMatchingContent() throws Exception {
        Path tracked = root.resolve("tracked.txt");
        Files.writeString(tracked, "before", StandardCharsets.UTF_8);
        Fixture fixture = fixture();
        String hostPath = tracked.toAbsolutePath().normalize().toString();
        ToolArguments arguments = arguments(Map.of("path", hostPath, "content", "after"));

        var result = fixture.operations.execute(
                "file.write",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-reconcile",
                "tool-call-reconcile",
                "idempotency-reconcile",
                "policy-reconcile",
                arguments);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(tracked)).isEqualTo("after");
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture fixture(LocalWorkspaceRootRegistry registry) {
        return fixture(registry, null);
    }

    private Fixture fixture(LocalWorkspaceRootRegistry registry, InMemorySessionChangeLedger ledger) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceId workspaceId = new WorkspaceId("workspace-file-read");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-file-read");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-file-read");
        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            realRoot = root.toAbsolutePath().normalize();
        }
        locations.register(locationRef, realRoot);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        LocalWorkspaceLocationStore.fingerprintFor(realRoot),
                        now)
                .activate(now);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("project-file-read"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));
        var files = new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        LocalWorkspaceScope scope;
        if (registry != null) {
            List<LocalAllowedDirectory> dirs = new ArrayList<>();
            for (LocalWorkspaceRoot r : registry.allRoots()) {
                WorkspaceId wsId = new WorkspaceId(
                        r.alias().isMain()
                                ? "workspace-file-read"
                                : "ws-" + r.alias().value());
                LocalDirectoryPermission perm = r.permission().canWrite()
                        ? LocalDirectoryPermission.READ_WRITE
                        : LocalDirectoryPermission.READ_ONLY;
                try {
                    dirs.add(LocalAllowedDirectory.of(wsId, r.hostPath().toRealPath(), perm));
                } catch (IOException e) {
                    dirs.add(LocalAllowedDirectory.of(
                            wsId, r.hostPath().toAbsolutePath().normalize(), perm));
                }
            }
            scope = new LocalWorkspaceScope(dirs, 1L);
        } else {
            scope = LocalWorkspaceScope.initial(
                    LocalAllowedDirectory.of(workspaceId, realRoot, LocalDirectoryPermission.READ_WRITE));
        }
        var operations = new LocalFileToolOperations(
                workspaces,
                files,
                testMutations(workspaces, workspaceId, new ProjectId("project-file-read")),
                () -> "id-1",
                registry,
                null,
                scope,
                ledger);
        return new Fixture(workspaceId, operations);
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.read.input", "1.1.0", values);
    }

    private WorkspaceMutationProvider testMutations(
            InMemoryWorkspaceStore workspaces, WorkspaceId workspaceId, ProjectId projectId) {
        return new WorkspaceMutationProvider() {
            @Override
            public String providerId() {
                return "unused";
            }

            @Override
            public WorkspaceMutationCapabilities capabilities() {
                return new WorkspaceMutationCapabilities(false, false, "unused");
            }

            @Override
            public MutationResult create(CreateFileRequest request) {
                if ("existing.txt".equals(request.path().projectPath().value())) {
                    throw new WorkspaceMutationException(
                            MutationErrorCode.TARGET_EXISTS, request.path(), "logical target already exists");
                }
                try {
                    Path target = root;
                    for (String segment : request.path().projectPath().segments()) target = target.resolve(segment);
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.write(target, request.content());
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:test-create-result");
                    FileChange change = new FileChange(
                            FileChangeType.CREATE,
                            request.path().projectPath(),
                            null,
                            null,
                            new FileVersion(FileType.FILE, request.content().length, "sha256:" + "a".repeat(64)));
                    return new MutationResult(before, after, java.util.List.of(change), true, false);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }

            @Override
            public MutationResult write(WriteFileRequest request) {
                try {
                    Path target = root;
                    for (String segment : request.path().projectPath().segments()) target = target.resolve(segment);
                    Files.write(target, request.content());
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:test-patch-result");
                    FileChange change = new FileChange(
                            FileChangeType.REPLACE,
                            request.path().projectPath(),
                            null,
                            new FileVersion(FileType.FILE, request.content().length, "sha256:" + "a".repeat(64)),
                            new FileVersion(FileType.FILE, request.content().length, "sha256:" + "b".repeat(64)));

                    return new MutationResult(before, after, java.util.List.of(change), true, false);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                try {
                    Files.deleteIfExists(
                            root.resolve(request.path().projectPath().toString()));
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:delete-test");
                    FileChange change = new FileChange(
                            FileChangeType.DELETE,
                            request.path().projectPath(),
                            null,
                            new FileVersion(FileType.FILE, 0, "sha256:zero"),
                            null);
                    return new MutationResult(before, after, java.util.List.of(change), true, false);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                try {
                    Files.move(
                            root.resolve(request.source().projectPath().toString()),
                            root.resolve(request.destination().projectPath().toString()));
                    WorkspaceRevision before =
                            workspaces.find(workspaceId).orElseThrow().revision();
                    WorkspaceRevision after = new WorkspaceRevision(before.sequence() + 1, "sha256:move-test");
                    return new MutationResult(before, after, java.util.List.of(), true, false);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }
        };
    }

    private record Fixture(WorkspaceId workspaceId, LocalFileToolOperations operations) {}

    @Test
    void rejectsWriteToReadOnlyAttachedRoot() throws Exception {
        LocalWorkspaceRoot mainRoot = LocalWorkspaceRoot.main(root, WorkspaceRootStrategy.GIT);
        Path docsRoot = root.resolveSibling("docs");
        Files.createDirectories(docsRoot);
        LocalWorkspaceRoot docs = LocalWorkspaceRoot.of(
                WorkspaceRootAlias.of("docs"),
                docsRoot,
                WorkspaceRootPermission.READ_ONLY,
                WorkspaceRootStrategy.PLAIN);
        LocalWorkspaceRootRegistry registry = LocalWorkspaceRootRegistry.builder()
                .addRoot(mainRoot)
                .addRoot(docs)
                .build();

        Fixture fixture = fixture(registry);

        var result = fixture.operations.execute(
                "file.create",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of(
                        "path",
                        docsRoot.resolve("guide.md")
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        "content",
                        "new content")));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "PERMISSION_DENIED")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_WRITE_PERMISSION");
    }

    @Test
    void rejectsRelativePathOrAlias() {
        Fixture fixture = fixture();
        var resultAlias = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "unknown:file.txt")));

        assertThat(resultAlias.successful()).isFalse();
        assertThat(resultAlias.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");

        var resultRelative = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "file.txt")));

        assertThat(resultRelative.successful()).isFalse();
        assertThat(resultRelative.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");
    }

    @Test
    void rejectsUnauthorizedHostPath() throws Exception {
        Fixture fixture = fixture();
        Path outside = root.resolveSibling("outside.txt").toAbsolutePath().normalize();
        var result = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", outside.toString())));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "ACCESS_DENIED")
                .containsEntry("failureCategory", "POLICY_DENIED")
                .containsEntry("failureActionCode", "REQUEST_DIRECTORY_AUTHORIZATION");
    }

    @Test
    void deletesEmptyDirectory() throws Exception {
        Path testdir = root.resolve("testdir");
        Files.createDirectories(testdir);
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.delete",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", testdir.toAbsolutePath().normalize().toString())));

        assertThat(result.successful()).isTrue();
        assertThat(Files.exists(testdir)).isFalse();
    }

    @Test
    void deletesRegularFileDirectlyWithoutQuarantineToken() throws Exception {
        Path trash = root.resolve("trash.txt");
        Files.writeString(trash, "delete me", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = trash.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.delete",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath)));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("path", hostPath)
                .doesNotContainKeys("quarantineToken", "changeSetId", "changeReviewArtifact");
        assertThat(Files.exists(trash)).isFalse();
    }

    @Test
    void recordsChangesIntoSessionLedger() {
        var ledger = new InMemorySessionChangeLedger();
        LocalWorkspaceRoot mainRoot = LocalWorkspaceRoot.main(root, WorkspaceRootStrategy.GIT);
        LocalWorkspaceRootRegistry registry = LocalWorkspaceRootRegistry.singleMain(mainRoot);
        Fixture f = fixture(registry, ledger);

        String hostPath = root.resolve("hello.txt").toAbsolutePath().normalize().toString();
        var createRes = f.operations.execute(
                "file.create",
                f.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", hostPath, "content", "hello world")));

        assertThat(createRes.successful()).isTrue();
        assertThat(ledger.compactedChanges(f.workspaceId)).hasSize(1);
        SessionFileChangeRecord record = ledger.compactedChanges(f.workspaceId).get(0);
        assertThat(record.path().projectPath().value()).isEqualTo("hello.txt");
    }
}
