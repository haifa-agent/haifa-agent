package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.haifa.agent.sandbox.api.GitWorktreeIsolationProvider;
import io.haifa.agent.sandbox.api.GitWorktreeRequest;
import io.haifa.agent.sandbox.api.IsolatedWorkspace;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorktreeToolOperationsTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final PrincipalRef OWNER = new PrincipalRef("operator", "user");

    @TempDir
    Path tempDir;

    private Path initialRoot;
    private Path childRoot;
    private ProjectId projectId;
    private Workspace initialWorkspace;
    private InMemoryWorkspaceStore workspaces;
    private InMemoryWorkspaceBindingStore bindings;
    private HostWorkspaceLocationStore locations;
    private AuthorizedWorkspaceProvisioning provisioning;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = tempDir.toRealPath();
        initialRoot = Files.createDirectories(tempDir.resolve("repository"));
        childRoot = Files.createDirectories(tempDir.resolve("managed-worktree"));
        projectId = new ProjectId("project-worktree-test");
        workspaces = new InMemoryWorkspaceStore();
        bindings = new InMemoryWorkspaceBindingStore();
        locations = new HostWorkspaceLocationStore();
        TimeProvider time = () -> NOW;

        WorkspaceLocationRef initialLocation = new WorkspaceLocationRef("location-initial");
        locations.register(initialLocation, initialRoot);
        WorkspaceBinding initialBinding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("binding-initial"),
                        initialLocation,
                        WorkspaceBindingMode.DIRECT,
                        OWNER,
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        HostWorkspaceLocationStore.fingerprintFor(initialRoot),
                        NOW)
                .activate(NOW);
        bindings.create(initialBinding);
        initialWorkspace = Workspace.provision(
                        new WorkspaceId("workspace-initial"),
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), initialBinding.id(), "local-guarded"),
                        WorkspaceRevision.initial("git:0123456789abcdef"),
                        NOW)
                .activate(NOW);
        workspaces.create(initialWorkspace);

        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        new TenantRef("tenant"),
                        OWNER,
                        "worktree-test",
                        "",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config").value(), "1"),
                        NOW,
                        Map.of())
                .assignDefaultWorkspace(initialWorkspace.id(), NOW));
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, () -> "unused", time);
        var initialScope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(initialWorkspace.id(), initialRoot));
        provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaces,
                bindings,
                locations,
                workspaceService,
                OWNER,
                time,
                initialScope,
                new InMemoryHostWorkspaceRegistryStore(),
                "repository");
    }

    @Test
    void activatesOnlyTheApprovedProviderCreatedWorktreeAndReturnsNoHostPath() {
        AtomicReference<GitWorktreeRequest> captured = new AtomicReference<>();
        AtomicInteger dispatched = new AtomicInteger();
        AtomicInteger acknowledged = new AtomicInteger();
        var workspaceAccess = new InMemoryWorkspaceAccessStore();
        workspaceAccess.replace(new WorkspaceAccess(
                new TenantRef("tenant"), OWNER, initialWorkspace.id(), WorkspaceAccessMode.DEVELOP));
        GitWorktreeIsolationProvider provider = provider(captured, new AtomicBoolean());
        var operations =
                new ProjectWorktreeToolOperations(provider, provisioning, () -> "identity-seed", workspaceAccess);

        ToolResult result = operations.execute(
                invocation(new ToolInvocationObserver() {
                    @Override
                    public void dispatched() {
                        dispatched.incrementAndGet();
                    }

                    @Override
                    public void acknowledged() {
                        acknowledged.incrementAndGet();
                    }
                }),
                access());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("sourceWorkspaceRef", initialWorkspace.id().value())
                .containsEntry("baseCommit", "0123456789abcdef")
                .containsEntry("branchName", "feat/controlled-worktree")
                .containsEntry("safeDisplayName", "feature-target")
                .containsEntry("source", HostWorkspaceRegistrySource.APPROVED_WORKTREE_CREATE.name())
                .containsEntry("status", "ACTIVE");
        assertThat(result.structuredData().toString()).doesNotContain(childRoot.toString());
        WorkspaceId child =
                new WorkspaceId(result.structuredData().get("workspaceRef").toString());
        assertThat(provisioning.scope().resolveExecutionDirectory(child, ".").workspaceId())
                .isEqualTo(child);
        assertThat(workspaceAccess.find(new TenantRef("tenant"), OWNER, child))
                .get()
                .satisfies(access -> assertThat(access.mode()).isEqualTo(WorkspaceAccessMode.DEVELOP));
        assertThat(captured.get().branchName()).isEqualTo("feat/controlled-worktree");
        assertThat(captured.get().baseCommit()).isEqualTo("0123456789abcdef");
        assertThat(dispatched).hasValue(1);
        assertThat(acknowledged).hasValue(1);
    }

    @Test
    void removesProviderWorktreeWhenPostCreationAcknowledgementFails() {
        AtomicBoolean released = new AtomicBoolean();
        var workspaceAccess = new InMemoryWorkspaceAccessStore();
        workspaceAccess.replace(new WorkspaceAccess(
                new TenantRef("tenant"), OWNER, initialWorkspace.id(), WorkspaceAccessMode.DEVELOP));
        var operations = new ProjectWorktreeToolOperations(
                provider(new AtomicReference<>(), released), provisioning, () -> "identity-seed", workspaceAccess);

        ToolResult result = operations.execute(
                invocation(new ToolInvocationObserver() {
                    @Override
                    public void dispatched() {}

                    @Override
                    public void acknowledged() {
                        throw new IllegalStateException("acknowledgement failed");
                    }
                }),
                access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("stableFailureCode", "WORKTREE_CREATE_FAILED")
                .containsEntry("retryable", false);
        assertThat(released).isTrue();
        assertThat(workspaceAccess.list(new TenantRef("tenant"), OWNER))
                .containsExactly(new WorkspaceAccess(
                        new TenantRef("tenant"), OWNER, initialWorkspace.id(), WorkspaceAccessMode.DEVELOP));
        assertThat(provisioning.scope().allowedDirectories()).hasSize(1);
    }

    @Test
    void missingParentDevelopAccessRejectsWorktreeCreationBeforeProviderDispatch() {
        AtomicReference<GitWorktreeRequest> captured = new AtomicReference<>();
        var operations = new ProjectWorktreeToolOperations(
                provider(captured, new AtomicBoolean()),
                provisioning,
                () -> "identity-seed",
                new InMemoryWorkspaceAccessStore());

        assertThatThrownBy(() -> operations.execute(invocation(ToolInvocationObserver.noop()), access()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKSPACE_ACCESS_UNAVAILABLE");
        assertThat(captured.get()).isNull();
        assertThat(provisioning.scope().allowedDirectories()).hasSize(1);
    }

    @Test
    void constructionRejectsMissingWorkspaceAccessAuthority() {
        assertThatThrownBy(() -> new ProjectWorktreeToolOperations(
                        provider(new AtomicReference<>(), new AtomicBoolean()),
                        provisioning,
                        () -> "identity-seed",
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("workspaceAccess must not be null");
    }

    private GitWorktreeIsolationProvider provider(
            AtomicReference<GitWorktreeRequest> captured, AtomicBoolean released) {
        return new GitWorktreeIsolationProvider() {
            @Override
            public IsolatedWorkspace createWorktree(GitWorktreeRequest request) {
                captured.set(request);
                locations.register(request.childLocationRef(), childRoot);
                WorkspaceBinding binding = WorkspaceBinding.provision(
                                request.childBindingId(),
                                request.childLocationRef(),
                                WorkspaceBindingMode.COPY_ON_WRITE,
                                request.owner(),
                                request.narrowedCapabilities(),
                                request.narrowedPermissions(),
                                HostWorkspaceLocationStore.fingerprintFor(childRoot),
                                NOW)
                        .activate(NOW);
                bindings.create(binding);
                workspaces.create(Workspace.provision(
                                request.childWorkspaceId(),
                                projectId,
                                WorkspacePurpose.CHILD,
                                new WorkspaceRoot(ProjectPath.root(), binding.id(), "local-guarded"),
                                WorkspaceRevision.initial("git:" + request.baseCommit()),
                                NOW)
                        .activate(NOW));
                return new IsolatedWorkspace(
                        request.parentWorkspaceId(),
                        request.childWorkspaceId(),
                        request.childBindingId(),
                        request.childLocationRef(),
                        WorkspaceBindingMode.COPY_ON_WRITE,
                        initialWorkspace.revision(),
                        NOW);
            }

            @Override
            public void releaseWorktree(WorkspaceId childWorkspaceId, boolean confirmedDiscard) {
                released.set(true);
            }
        };
    }

    private ToolInvocationRequest invocation(ToolInvocationObserver observer) {
        var binding = new ProjectToolCatalog()
                .freeze(
                        Set.of(ProjectWorktreeToolOperations.TOOL_NAME),
                        Set.of("execution.run"),
                        true,
                        catalogOnlyProvider())
                .snapshot()
                .bindings()
                .getFirst();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-worktree"),
                new AgentRunId("run-worktree"),
                new TenantRef("tenant"),
                OWNER,
                new ToolArguments(
                        "haifa.workspace.worktree.create.input",
                        "1.0.0",
                        Map.of(
                                "sourceWorkspaceRef",
                                initialWorkspace.id().value(),
                                "baseCommit",
                                "0123456789abcdef",
                                "branchName",
                                "feat/controlled-worktree",
                                "targetName",
                                "feature-target",
                                "deliveryIntent",
                                "pull-request")),
                NOW.plusSeconds(30),
                Optional.of("worktree-key"),
                () -> false,
                Map.of(),
                observer);
    }

    private RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(initialWorkspace.id(), Set.of("execution.run"));
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
}
