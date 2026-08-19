package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.domain.ProjectId;
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

        var first = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "large.txt", "maxBytes", 8, "maxLines", 1)));
        String cursor = (String) first.structuredData().get("nextCursor");
        var second = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "large.txt", "cursor", cursor, "maxBytes", 8, "maxLines", 1)));

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
                arguments(Map.of("path", "large.txt", "cursor", cursor)));
        assertThat(stale.successful()).isFalse();
        assertThat(stale.structuredData())
                .containsEntry("errorCode", "FILE_CURSOR_STALE")
                .containsEntry("failureActionCode", "RESTART_READ_FROM_CURRENT_VERSION")
                .containsEntry("retryable", true)
                .containsEntry("maximumAutomaticRetries", 1);
    }

    @Test
    void appliesModelVisibleContextPatchWithoutSeparatePathArgument() throws Exception {
        Files.writeString(root.resolve("source.txt"), "anchor\nold\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(
                        Map.of(
                                "patch",
                                """
                        *** Begin Patch
                        *** Update File: source.txt
                        @@ anchor
                        -old
                        +new
                        *** End Patch
                        """)));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData()).containsEntry("complete", true);
        assertThat(Files.readString(root.resolve("source.txt"))).isEqualTo("anchor\nnew\n");
    }

    @Test
    void returnsKnownToolFailureWhenWorkspaceMutationIsRejected() {
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.create",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "existing.txt", "content", "replacement")));

        assertThat(result.successful()).isFalse();
        assertThat(result.summary()).isEqualTo("Workspace mutation failed: TARGET_EXISTS (path=existing.txt)");
        assertThat(result.structuredData())
                .containsEntry("errorCode", "TARGET_EXISTS")
                .containsEntry("failureActionCode", "USE_FILE_WRITE_OR_PATCH")
                .containsEntry("retryable", false)
                .containsEntry("path", "existing.txt");
    }

    @Test
    void givesDeterministicCreateWriteAndSensitivePathRecoveryActions() {
        Fixture fixture = fixture();

        var missingWrite = fixture.operations.execute(
                "file.write",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", "missing.txt", "content", "new")));
        var sensitiveRead = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("path", ".env")));

        assertThat(missingWrite.structuredData())
                .containsEntry("stableFailureCode", "PATH_NOT_FOUND")
                .containsEntry("failureActionCode", "USE_FILE_CREATE")
                .containsEntry("retryable", false);
        assertThat(sensitiveRead.structuredData())
                .containsEntry("stableFailureCode", "SENSITIVE_PATH")
                .containsEntry("failureActionCode", "USER_ACTION_REQUIRED")
                .containsEntry("retryable", false)
                .containsEntry("maximumAutomaticRetries", 0);
        assertThat(sensitiveRead.structuredData().get("failureAction").toString())
                .contains("do not rename, relocate, or copy");
    }

    private Fixture fixture() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceId workspaceId = new WorkspaceId("workspace-file-read");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-file-read");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-file-read");
        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.READ_ONLY,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
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
        var operations =
                new LocalFileToolOperations(workspaces, files, testMutations(workspaces, workspaceId), () -> "id-1");
        return new Fixture(workspaceId, operations);
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.read.input", "1.1.0", values);
    }

    private WorkspaceMutationProvider testMutations(InMemoryWorkspaceStore workspaces, WorkspaceId workspaceId) {
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
                throw new WorkspaceMutationException(
                        MutationErrorCode.TARGET_EXISTS, request.path(), "logical target already exists");
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
                    return new MutationResult(
                            new FileChangeSetId("change-set-test"),
                            FileChangeSetStatus.APPLIED,
                            before,
                            after,
                            java.util.List.of(),
                            true,
                            false);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }

            @Override
            public MutationResult delete(DeleteFileRequest request) {
                throw new AssertionError("not used");
            }

            @Override
            public MutationResult move(MoveFileRequest request) {
                throw new AssertionError("not used");
            }
        };
    }

    private record Fixture(WorkspaceId workspaceId, LocalFileToolOperations operations) {}
}
