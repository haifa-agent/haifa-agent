package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.delivery.AttributionStatus;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryBaseline;
import io.haifa.agent.application.project.product.coding.delivery.RunRepositoryBaselineRegistry;
import io.haifa.agent.application.project.tool.ProjectToolCallContext;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.git.GitRepositoryRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceMutationService;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileToolOperationsTest {
    @TempDir
    Path root;

    @BeforeEach
    void canonicalizeTempDirectory() throws IOException {
        root = root.toRealPath();
    }

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
    void returnsStableFeedbackForInvalidFileArguments() {
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.list",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                "policy-1",
                arguments(Map.of("recursive", true)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("stableFailureCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "READ_CURRENT_STATE")
                .containsEntry("retryable", false);
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
    void returnsKnownToolFailureWhenWorkspaceMutationIsRejected() throws IOException {
        Fixture fixture = fixture();
        Files.writeString(root.resolve("existing.txt"), "existing", StandardCharsets.UTF_8);
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
        return fixture(null, null, false);
    }

    private Fixture fixture(InMemorySessionChangeLedger ledger) {
        return fixture(ledger, null, false);
    }

    private Fixture fixture(InMemorySessionChangeLedger ledger, RunRepositoryBaselineRegistry repositoryBaselines) {
        return fixture(ledger, repositoryBaselines, false);
    }

    private Fixture fixture(boolean workspaceAttachmentDisclosed) {
        return fixture(null, null, workspaceAttachmentDisclosed);
    }

    private Fixture fixture(
            InMemorySessionChangeLedger ledger,
            RunRepositoryBaselineRegistry repositoryBaselines,
            boolean workspaceAttachmentDisclosed) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceId workspaceId = new WorkspaceId("workspace-file-read");
        ProjectId projectId = new ProjectId("project-file-read");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-file-read");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-file-read");
        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
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
                        HostWorkspaceLocationStore.fingerprintFor(realRoot),
                        now)
                .activate(now);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));
        var files = new HostWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        HostWorkspaceScope scope = HostWorkspaceScope.initial(
                AuthorizedHostDirectory.of(workspaceId, realRoot, HostDirectoryPermission.READ_WRITE));
        var sequence = new AtomicInteger();
        var identifiers =
                (io.haifa.agent.common.id.IdentifierGenerator) () -> "file-tool-test-" + sequence.incrementAndGet();
        PrincipalRef owner = new PrincipalRef("owner", "user");
        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        new TenantRef("local"),
                        owner,
                        "file-tool-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        now,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, now));
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, identifiers, () -> now);
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId, workspaces, bindings, locations, workspaceService, owner, () -> now, scope);
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        var operations = new LocalFileToolOperations(
                workspaces,
                files,
                mutations,
                identifiers,
                () -> now,
                provisioning,
                ledger,
                repositoryBaselines,
                workspaceAttachmentDisclosed);
        return new Fixture(workspaceId, operations);
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.read.input", "1.1.0", values);
    }

    private record Fixture(WorkspaceId workspaceId, LocalFileToolOperations operations) {}

    @Test
    void establishesRepositoryBaselineBeforeFirstPhysicalWrite() {
        Path target = root.resolve("before-write.txt");
        AtomicInteger captures = new AtomicInteger();
        var registry = new RunRepositoryBaselineRegistry(
                (boundary, candidate) -> candidate.equals(root)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (context, repository) -> {
                    assertThat(Files.exists(target)).isFalse();
                    captures.incrementAndGet();
                    return cleanBaseline(repository);
                });
        Fixture fixture = fixture(null, registry);

        var result = fixture.operations.execute(
                callContext(fixture.workspaceId, "run-baseline"),
                "file.create",
                arguments(Map.of("path", target.toString(), "content", "created")));

        assertThat(result.successful()).isTrue();
        assertThat(Files.exists(target)).isTrue();
        assertThat(captures).hasValue(1);
    }

    @Test
    void doesNotWriteWhenRepositoryBaselineFails() {
        Path target = root.resolve("blocked-write.txt");
        var registry = new RunRepositoryBaselineRegistry(
                (boundary, candidate) -> candidate.equals(root)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (context, repository) -> {
                    throw new IllegalStateException("git unavailable");
                });
        Fixture fixture = fixture(null, registry);

        var result = fixture.operations.execute(
                callContext(fixture.workspaceId, "run-blocked"),
                "file.create",
                arguments(Map.of("path", target.toString(), "content", "must not exist")));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "REPOSITORY_BASELINE_UNAVAILABLE");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void keepsFileAuthorizationIndependentWhenGitInspectionIsUnavailable() throws Exception {
        Path target = root.resolve("git-unavailable.txt");
        var registry = new RunRepositoryBaselineRegistry(
                (boundary, candidate) -> HostGitInspectionStatus.UNAVAILABLE, (context, repository) -> {
                    throw new AssertionError("capture must not run without a located repository");
                });
        Fixture fixture = fixture(null, registry);

        var result = fixture.operations.execute(
                callContext(fixture.workspaceId, "run-git-unavailable"),
                "file.create",
                arguments(Map.of("path", target.toString(), "content", "authorized")));

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("authorized");
        assertThat(registry.attributionStatus("run-git-unavailable")).isEqualTo(AttributionStatus.ATTRIBUTION_PARTIAL);
    }

    private static ProjectToolCallContext callContext(WorkspaceId workspaceId, String runRef) {
        return new ProjectToolCallContext(
                new TenantRef("tenant"),
                workspaceId,
                new PrincipalRef("operator", "user"),
                runRef,
                "tool-call",
                "idempotency",
                "policy");
    }

    private static RepositoryBaseline cleanBaseline(GitRepositoryRef repository) {
        return new RepositoryBaseline(
                repository,
                "abc123",
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                AttributionStatus.COMPLETE);
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
                .containsEntry("failureCategory", "WORKSPACE_SCOPE_DENIED")
                .containsEntry("failureActionCode", "USE_AUTHORIZED_WORKSPACE_PATH");
    }

    @Test
    void requestsDirectoryAuthorizationForUnauthorizedHostPathWhenAttachmentIsDisclosed() throws Exception {
        Fixture fixture = fixture(true);
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
                .containsEntry("failureCategory", "WORKSPACE_SCOPE_DENIED")
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
        Fixture f = fixture(ledger);

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
