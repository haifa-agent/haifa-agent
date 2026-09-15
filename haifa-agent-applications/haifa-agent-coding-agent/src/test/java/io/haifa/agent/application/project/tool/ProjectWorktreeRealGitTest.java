package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.workspace.InMemoryWorkspaceAccessStore;
import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.InMemoryHostWorkspaceRegistryStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.host.HostGitWorktreeIsolationProvider;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorktreeRealGitTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final PrincipalRef OWNER = new PrincipalRef("operator", "user");

    @TempDir
    Path tempDir;

    private Path repoDir;
    private String baseCommit;
    private ProjectId projectId;
    private Workspace initialWorkspace;
    private InMemoryWorkspaceStore workspaces;
    private InMemoryWorkspaceBindingStore bindings;
    private HostWorkspaceLocationStore locations;
    private InMemoryHostWorkspaceRegistryStore registryStore;
    private AuthorizedWorkspaceProvisioning provisioning;
    private HostGitWorktreeIsolationProvider provider;
    private InMemoryWorkspaceAccessStore workspaceAccess;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = tempDir.toRealPath();
        repoDir = Files.createDirectories(tempDir.resolve("repository"));
        runGit(repoDir, "init");
        runGit(repoDir, "config", "core.autocrlf", "false");
        runGit(repoDir, "config", "user.email", "test@example.invalid");
        runGit(repoDir, "config", "user.name", "Haifa Test");
        Files.writeString(repoDir.resolve("tracked.txt"), "base content\n", StandardCharsets.UTF_8);
        runGit(repoDir, "add", "tracked.txt");
        runGit(repoDir, "commit", "-m", "initial commit");
        baseCommit = runGit(repoDir, "rev-parse", "HEAD").trim();

        projectId = new ProjectId("project-real-git-test");
        workspaces = new InMemoryWorkspaceStore();
        bindings = new InMemoryWorkspaceBindingStore();
        locations = new HostWorkspaceLocationStore();
        registryStore = new InMemoryHostWorkspaceRegistryStore();
        TimeProvider time = () -> NOW;

        WorkspaceLocationRef initialLocation = new WorkspaceLocationRef("location-parent");
        locations.register(initialLocation, repoDir);
        WorkspaceBinding initialBinding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("binding-parent"),
                        initialLocation,
                        WorkspaceBindingMode.DIRECT,
                        OWNER,
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        HostWorkspaceLocationStore.fingerprintFor(repoDir),
                        NOW)
                .activate(NOW);
        bindings.create(initialBinding);
        initialWorkspace = Workspace.provision(
                        new WorkspaceId("workspace-parent"),
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), initialBinding.id(), "local-guarded"),
                        WorkspaceRevision.initial("git:" + baseCommit),
                        NOW)
                .activate(NOW);
        workspaces.create(initialWorkspace);

        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        new TenantRef("tenant"),
                        OWNER,
                        "real-git-project",
                        "",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config").value(), "1"),
                        NOW,
                        Map.of())
                .assignDefaultWorkspace(initialWorkspace.id(), NOW));
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, () -> "unused", time);
        var initialScope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(initialWorkspace.id(), repoDir));
        provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaces,
                bindings,
                locations,
                workspaceService,
                OWNER,
                time,
                initialScope,
                registryStore,
                "repository");

        provider = new HostGitWorktreeIsolationProvider(workspaces, bindings, locations, "git", time);
        workspaceAccess = new InMemoryWorkspaceAccessStore();
        workspaceAccess.replace(new WorkspaceAccess(
                new TenantRef("tenant"), OWNER, initialWorkspace.id(), WorkspaceAccessMode.DEVELOP));
    }

    @Test
    void createsWorktreeAtExplicitTargetPathWithoutFixedDirectory() throws Exception {
        Path targetPath = tempDir.resolve("custom-worktree");
        var operations =
                new ProjectWorktreeToolOperations(provider, provisioning, () -> "real-git-seed", workspaceAccess);

        ToolResult result = operations.execute(
                invocation(targetPath.toString(), "feat/custom-worktree", ToolInvocationObserver.noop()), access());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("sourceWorkspaceRef", initialWorkspace.id().value())
                .containsEntry("baseCommit", baseCommit)
                .containsEntry("branchName", "feat/custom-worktree")
                .containsEntry("safeDisplayName", "custom-worktree")
                .containsEntry("rootPath", targetPath.toString())
                .containsEntry("source", HostWorkspaceRegistrySource.APPROVED_WORKTREE_CREATE.name())
                .containsEntry("status", "ACTIVE");

        // 1. Worktree created at exact targetPath and tracked file is readable and isolated
        assertThat(Files.isDirectory(targetPath)).isTrue();
        assertThat(Files.readString(targetPath.resolve("tracked.txt"), StandardCharsets.UTF_8)
                        .trim())
                .isEqualTo("base content");

        Files.writeString(targetPath.resolve("tracked.txt"), "child modified content\n", StandardCharsets.UTF_8);
        assertThat(Files.readString(repoDir.resolve("tracked.txt"), StandardCharsets.UTF_8)
                        .trim())
                .isEqualTo("base content");

        // 2. Verified in scope and registry
        WorkspaceId childId =
                new WorkspaceId(result.structuredData().get("workspaceRef").toString());
        assertThat(provisioning.scope().allowedDirectories().stream()
                        .anyMatch(d ->
                                d.workspaceId().equals(childId) && d.realPath().equals(targetPath)))
                .isTrue();

        // 3. No .haifa-agent-worktrees directory was generated anywhere
        assertThat(Files.exists(repoDir.resolve(".haifa-agent-worktrees"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".haifa-agent-worktrees"))).isFalse();
        assertThat(Files.exists(targetPath.resolve(".haifa-agent-worktrees"))).isFalse();
    }

    @Test
    void failedRegistrationCleansUpCreatedTargetPathAndBranchLeavingParentIntact() throws Exception {
        Path customParent = Files.createDirectories(tempDir.resolve("parent-folder"));
        Path targetPath = customParent.resolve("target-worktree");
        Path siblingFile = customParent.resolve("sibling.txt");
        Files.writeString(siblingFile, "keep me untouched\n", StandardCharsets.UTF_8);

        // Pre-register location to trigger registration collision during worktree creation
        String branchName = "feat/failing-worktree";
        String identity = PolicyDigest.sha256Fields(List.of(
                "coding-worktree-v1",
                initialWorkspace.id().value(),
                baseCommit,
                branchName,
                targetPath.toString(),
                "tool-call-real-git",
                "real-git-seed"));
        WorkspaceLocationRef childLocation = new WorkspaceLocationRef("location-worktree-" + identity.substring(0, 32));
        locations.register(childLocation, repoDir);

        var operations =
                new ProjectWorktreeToolOperations(provider, provisioning, () -> "real-git-seed", workspaceAccess);

        ToolResult result = operations.execute(
                invocation(targetPath.toString(), branchName, ToolInvocationObserver.noop()), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("stableFailureCode", "WORKTREE_CREATE_FAILED")
                .containsEntry("retryable", false);

        // Target path is cleaned up
        assertThat(Files.exists(targetPath)).isFalse();

        // Git branch was rolled back
        String branches = runGit(repoDir, "branch", "--list", branchName).trim();
        assertThat(branches).isEmpty();

        // Parent directory and sibling file are intact and untouched
        assertThat(Files.isDirectory(customParent)).isTrue();
        assertThat(Files.readString(siblingFile, StandardCharsets.UTF_8).trim()).isEqualTo("keep me untouched");

        // Repository is intact
        assertThat(Files.readString(repoDir.resolve("tracked.txt"), StandardCharsets.UTF_8)
                        .trim())
                .isEqualTo("base content");
    }

    @Test
    void failedAcknowledgementCleansUpCreatedTargetPathLeavingParentIntact() throws Exception {
        Path customParent = Files.createDirectories(tempDir.resolve("parent-ack-folder"));
        Path targetPath = customParent.resolve("target-ack-worktree");
        Path siblingFile = customParent.resolve("sibling-ack.txt");
        Files.writeString(siblingFile, "keep me ack untouched\n", StandardCharsets.UTF_8);

        var operations =
                new ProjectWorktreeToolOperations(provider, provisioning, () -> "real-git-ack-seed", workspaceAccess);

        ToolInvocationObserver failingObserver = new ToolInvocationObserver() {
            @Override
            public void dispatched() {}

            @Override
            public void acknowledged() {
                throw new IllegalStateException("simulated post-creation failure");
            }
        };

        ToolResult result = operations.execute(
                invocation(targetPath.toString(), "feat/ack-failing-worktree", failingObserver), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("stableFailureCode", "WORKTREE_CREATE_FAILED")
                .containsEntry("retryable", false);

        // Target path is cleaned up
        assertThat(Files.exists(targetPath)).isFalse();

        // Parent directory and sibling file are intact and untouched
        assertThat(Files.isDirectory(customParent)).isTrue();
        assertThat(Files.readString(siblingFile, StandardCharsets.UTF_8).trim()).isEqualTo("keep me ack untouched");

        // Repository is intact
        assertThat(Files.readString(repoDir.resolve("tracked.txt"), StandardCharsets.UTF_8)
                        .trim())
                .isEqualTo("base content");
    }

    private ToolInvocationRequest invocation(String targetPath, String branchName, ToolInvocationObserver observer) {
        var binding = new ProjectToolCatalog()
                .freeze(
                        Set.of(ProjectWorktreeToolOperations.TOOL_NAME),
                        Set.of("execution_run"),
                        true,
                        catalogOnlyProvider())
                .snapshot()
                .bindings()
                .getFirst();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-real-git"),
                new AgentRunId("run-real-git"),
                new TenantRef("tenant"),
                OWNER,
                new ToolArguments(
                        "haifa.workspace.worktree.create.input",
                        "3.0.0",
                        Map.of(
                                "sourceWorkspaceRef",
                                initialWorkspace.id().value(),
                                "baseCommit",
                                baseCommit,
                                "branchName",
                                branchName,
                                "targetPath",
                                targetPath,
                                "deliveryIntent",
                                "pull-request")),
                NOW.plusSeconds(30),
                Optional.of("worktree-key"),
                () -> false,
                Map.of(),
                observer);
    }

    private RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(initialWorkspace.id(), Set.of("execution_run"));
    }

    private static ToolProvider catalogOnlyProvider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                throw new AssertionError("catalog-only provider");
            }
        };
    }

    private static String runGit(Path directory, String... command) throws Exception {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add("git");
        fullCommand.addAll(List.of(command));
        Process process = new ProcessBuilder(fullCommand)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Git command timed out: " + fullCommand);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Git command failed (" + process.exitValue() + "): " + output);
        }
        return output;
    }
}
