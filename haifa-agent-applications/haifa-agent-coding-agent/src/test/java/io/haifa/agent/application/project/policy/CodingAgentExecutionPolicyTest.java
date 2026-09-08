package io.haifa.agent.application.project.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.workspace.InMemoryWorkspaceAccessStore;
import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAgentExecutionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("actor", "user");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace");
    private static final SandboxProfileRef PROFILE = new SandboxProfileRef("test", "1");

    @TempDir
    Path root;

    @Test
    void readAccessAllowsOnlyTheExactInternalGitReadProtocol() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.READ);

        assertThatCode(() -> fixture.policy()
                        .authorize(
                                internal(List.of("status", "--porcelain=v1", "--untracked-files=normal")),
                                ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fixture.policy()
                        .authorize(
                                internal(List.of("push", "origin", "main")), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> fixture.policy()
                        .authorize(userCommand("git status"), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("MODE_DENIED");
    }

    @Test
    void readAccessAllowsEveryExactInternalGitProbe() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.READ);

        for (List<String> suffix : allowedInternalGitSuffixes()) {
            assertThatCode(() ->
                            fixture.policy().authorize(internal(suffix), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                    .as(suffix.toString())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void readAccessRejectsInternalGitArgumentExpansionAndWrites() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.READ);

        for (List<String> suffix : forbiddenInternalGitSuffixes()) {
            assertThatThrownBy(() ->
                            fixture.policy().authorize(internal(suffix), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                    .as(suffix.toString())
                    .isInstanceOf(ExecutionRejectedException.class)
                    .hasMessageContaining("allowlist");
        }
    }

    @Test
    void developAllowsTheFixedCliRequestButManagedAndForgedRuntimeFailClosed() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.DEVELOP);

        assertThatCode(() -> fixture.policy()
                        .authorize(userCommand("git status"), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fixture.policy()
                        .authorize(userCommand("git status"), ExecutionPolicyEntryPoint.MANAGED_SESSION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("managed");
        assertThatThrownBy(() -> fixture.policy()
                        .authorize(runtimeRequest(new ToolCallId("forged")), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void userCommandRejectsExistingClassifierHardDeniesAtTheBrokerBoundary() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.DEVELOP);

        assertThatThrownBy(() -> fixture.policy()
                        .authorize(userCommand("git -C ../other status"), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("classifier");
        assertThatThrownBy(() -> fixture.policy()
                        .authorize(userCommand("gh auth token"), ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("classifier");
    }

    @Test
    void replayRereadsCurrentWorkspaceAccessAndFailsClosedAfterRevoke() throws Exception {
        Fixture fixture = fixture(WorkspaceAccessMode.DEVELOP);
        ExecutionRequest request = userCommand("git status");

        assertThatCode(() -> fixture.policy().authorize(request, ExecutionPolicyEntryPoint.FIRST_EXECUTION))
                .doesNotThrowAnyException();
        fixture.access().delete(TENANT, PRINCIPAL, WORKSPACE);

        assertThatThrownBy(() -> fixture.policy().authorize(request, ExecutionPolicyEntryPoint.IDEMPOTENT_REPLAY))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ACCESS_UNAVAILABLE");
    }

    private Fixture fixture(WorkspaceAccessMode mode) throws Exception {
        Path realRoot = root.toRealPath();
        var projects = new InMemoryProjectStore();
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new HostWorkspaceLocationStore();
        ProjectId projectId = new ProjectId("project");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location");
        locations.register(locationRef, realRoot);
        bindings.create(WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        PRINCIPAL,
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        HostWorkspaceLocationStore.fingerprintFor(realRoot),
                        NOW)
                .activate(NOW));
        workspaces.create(Workspace.provision(
                        WORKSPACE,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial("revision"),
                        NOW)
                .activate(NOW));
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaces,
                bindings,
                locations,
                new WorkspaceService(projects, workspaces, bindings, () -> "id", () -> NOW),
                PRINCIPAL,
                () -> NOW,
                HostWorkspaceScope.initial(AuthorizedHostDirectory.of(WORKSPACE, realRoot)));
        var access = new InMemoryWorkspaceAccessStore();
        access.createIfAbsent(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, mode));
        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory();
        var runtime = new RuntimeToolExecutionVerifier(
                ports.runs(),
                ports.state(),
                ports.interactions(),
                io.haifa.agent.runtime.core.tool.ToolRequestCanonicalizer.identity(),
                (run, binding, request) ->
                        new PolicyDecision(PolicyEffect.ALLOW, Optional.empty(), "ALLOW", "Allowed", "requirement"));
        return new Fixture(
                new CodingAgentExecutionPolicy(
                        runtime,
                        new io.haifa.agent.application.project.tool.ProjectExecutionRecoveryAuthorization(
                                ports.state(), ports.interactions()),
                        access,
                        provisioning,
                        TENANT,
                        PRINCIPAL,
                        ExecutionEnvironmentRef.empty(),
                        ExecutionEnvironmentRef.empty(),
                        PROFILE,
                        PROFILE,
                        ExecutionScratchSpaceSpec.genericRequired(),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        4096,
                        4),
                access);
    }

    private static ExecutionRequest internal(List<String> suffix) {
        var argv = new java.util.ArrayList<>(List.of("git", "-c", "credential.interactive=never"));
        argv.addAll(suffix);
        return new ExecutionRequest(
                new ExecutionId("internal"),
                "internal-key",
                context(ExecutionOrigin.PRODUCT_INTERNAL, Set.of("execution.run", "git.read"), Optional.empty()),
                WORKSPACE,
                WorkspacePath.root(WORKSPACE),
                ExecutionCommand.direct(argv),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(15), internalGitOutputBudget(suffix), 64 * 1024, 4),
                PROFILE);
    }

    private static int internalGitOutputBudget(List<String> suffix) {
        return suffix.equals(List.of("status", "--porcelain=v1", "--untracked-files=normal"))
                        || suffix.equals(List.of("diff", "--numstat", "HEAD", "--"))
                ? 256 * 1024
                : 4096;
    }

    private static List<List<String>> allowedInternalGitSuffixes() {
        return List.of(
                List.of("rev-parse", "--show-toplevel"),
                List.of("rev-parse", "--is-inside-work-tree"),
                List.of("rev-parse", "HEAD"),
                List.of("rev-parse", "--verify", "HEAD"),
                List.of("symbolic-ref", "--short", "-q", "HEAD"),
                List.of("submodule", "status"),
                List.of("status", "--porcelain=v1", "--untracked-files=normal"),
                List.of("diff", "--numstat", "HEAD", "--"));
    }

    private static List<List<String>> forbiddenInternalGitSuffixes() {
        return List.of(
                List.of("status"),
                List.of("status", "--porcelain=v1", "--untracked-files=all"),
                List.of("-c", "core.pager=cat", "status", "--porcelain=v1", "--untracked-files=normal"),
                List.of("fetch", "origin"),
                List.of("push", "origin", "main"),
                List.of("config", "user.email", "attacker@example.test"));
    }

    private static ExecutionRequest userCommand(String command) {
        String digest = ExecutionRequest.digestWithScratch(
                io.haifa.agent.policy.api.PolicyDigest.sha256Fields(List.of(command, ".")),
                ExecutionScratchSpaceSpec.genericRequired());
        return new ExecutionRequest(
                new ExecutionId("user"),
                "user-key",
                context(ExecutionOrigin.PRODUCT_USER_COMMAND, Set.of("execution.run"), Optional.empty()),
                WORKSPACE,
                WorkspacePath.root(WORKSPACE),
                ExecutionCommand.shell(command),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(10), 16 * 1024 * 1024, 16 * 1024 * 1024, 4),
                PROFILE,
                io.haifa.agent.execution.api.ExecutionInput.none(),
                digest,
                ExecutionScratchSpaceSpec.genericRequired());
    }

    private static ExecutionRequest runtimeRequest(ToolCallId source) {
        return new ExecutionRequest(
                new ExecutionId("runtime"),
                "runtime-key",
                context(ExecutionOrigin.RUNTIME_TOOL, Set.of("execution.run"), Optional.of(source)),
                WORKSPACE,
                WorkspacePath.root(WORKSPACE),
                ExecutionCommand.shell("git status"),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4),
                PROFILE);
    }

    private static TrustedExecutionContext context(
            ExecutionOrigin origin, Set<String> capabilities, Optional<ToolCallId> source) {
        return new TrustedExecutionContext(TENANT, "run", PRINCIPAL, capabilities, origin, source);
    }

    private record Fixture(CodingAgentExecutionPolicy policy, InMemoryWorkspaceAccessStore access) {}
}
