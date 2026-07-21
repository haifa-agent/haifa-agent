package io.haifa.agent.sandbox.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
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
import io.haifa.agent.sandbox.api.EphemeralCopyRequest;
import io.haifa.agent.sandbox.api.GitWorktreeRequest;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.WorkspaceCopyBudget;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostSandboxTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @TempDir
    Path isolatedBase;

    @Test
    void runsWhitelistedArgvWithBoundedTimeoutAndHonestCapabilities() throws Exception {
        Fixture fixture = fixture(root, "workspace-1", "binding-1", "location-1");
        AtomicInteger ids = new AtomicInteger();
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.bindings,
                fixture.locations,
                () -> "session-" + ids.incrementAndGet(),
                () -> Instant.now());
        assertThat(provider.capabilities().networkIsolation()).isFalse();
        assertThat(provider.capabilities().filesystemMountIsolation()).isFalse();
        SandboxProfile profile = new SandboxProfile(
                new SandboxProfileRef("host-test", "1"), Set.of("java"), Set.of(), false, NetworkPolicy.ALLOW);
        try (var session = provider.open(profile, new WorkspaceMount(fixture.workspaceId, false))) {
            var version = session.execute(new SandboxExecution(
                    new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("java", "-version")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)));
            assertThat(version.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(version.exitCode()).isZero();

            copySleepClass(root);
            var timeout = session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of("java", "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(100), 1024, 1024, 2)));
            assertThat(timeout.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(timeout.processTreeTerminated()).isTrue();

            var cancelled = CompletableFuture.supplyAsync(() -> session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of("java", "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 1024, 1024, 2))));
            Thread.sleep(100);
            assertThat(session.cancel()).isTrue();
            assertThat(cancelled.get(5, TimeUnit.SECONDS).status()).isEqualTo(SandboxProcessStatus.CANCELLED);
        }

        assertThatThrownBy(() -> provider.open(
                        new SandboxProfile(
                                new SandboxProfileRef("deny", "1"),
                                Set.of("java"),
                                Set.of(),
                                false,
                                NetworkPolicy.DENY),
                        new WorkspaceMount(fixture.workspaceId, false)))
                .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                        .isEqualTo("NETWORK_ISOLATION_UNAVAILABLE"));
    }

    @Test
    void createsBudgetedEphemeralCopyWithNarrowedAuthorityAndSafeRelease() throws Exception {
        Files.writeString(root.resolve("visible.txt"), "visible");
        Files.writeString(root.resolve(".env"), "secret");
        Fixture fixture = fixture(root, "workspace-copy-parent", "binding-copy-parent", "location-copy-parent");
        var provider = new HostWorkspaceIsolationProvider(
                fixture.workspaces,
                fixture.bindings,
                fixture.locations,
                SensitivePathPolicy.defaults(),
                isolatedBase,
                () -> NOW);
        var isolated = provider.createEphemeralCopy(new EphemeralCopyRequest(
                fixture.workspaceId,
                new WorkspaceId("workspace-copy-child"),
                new WorkspaceBindingId("binding-copy-child"),
                new WorkspaceLocationRef("location-copy-child"),
                new PrincipalRef("child", "agent"),
                WorkspaceCapabilitySet.readWriteFiles(),
                WorkspacePermissionSet.readWrite(),
                new WorkspaceCopyBudget(100, 1024, 4096, Duration.ofSeconds(5))));
        Path child = fixture.locations.resolveForTrustedProvider(isolated.locationRef());
        assertThat(Files.readString(child.resolve("visible.txt"))).isEqualTo("visible");
        assertThat(Files.exists(child.resolve(".env"))).isFalse();
        Files.writeString(child.resolve("child-only.txt"), "child");
        assertThat(Files.exists(root.resolve("child-only.txt"))).isFalse();

        provider.release(isolated.childWorkspaceId());
        assertThat(Files.exists(child)).isFalse();
        assertThat(fixture.workspaces
                        .find(isolated.childWorkspaceId())
                        .orElseThrow()
                        .status())
                .isEqualTo(io.haifa.agent.project.workspace.WorkspaceStatus.RELEASED);
    }

    @Test
    void gitWorktreeIsIsolatedAndDirtyReleaseRequiresExplicitDiscard() throws Exception {
        run(root, "git", "init");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "config", "user.name", "Haifa Test");
        Files.writeString(root.resolve("tracked.txt"), "base\n");
        run(root, "git", "add", "tracked.txt");
        run(root, "git", "commit", "-m", "base");
        String commit = run(root, "git", "rev-parse", "HEAD").trim();
        Fixture fixture = fixture(root, "workspace-git-parent", "binding-git-parent", "location-git-parent");
        var provider = new HostGitWorktreeIsolationProvider(
                fixture.workspaces, fixture.bindings, fixture.locations, isolatedBase, "git", () -> NOW);
        var child = provider.createWorktree(new GitWorktreeRequest(
                fixture.workspaceId,
                new WorkspaceId("workspace-git-child"),
                new WorkspaceBindingId("binding-git-child"),
                new WorkspaceLocationRef("location-git-child"),
                new PrincipalRef("child", "agent"),
                commit,
                WorkspaceCapabilitySet.readWriteFiles(),
                WorkspacePermissionSet.readWrite()));
        Path childRoot = fixture.locations.resolveForTrustedProvider(child.locationRef());
        Files.writeString(childRoot.resolve("tracked.txt"), "child\n");
        assertThat(Files.readString(root.resolve("tracked.txt"))).isEqualTo("base\n");
        assertThatThrownBy(() -> provider.releaseWorktree(child.childWorkspaceId(), false))
                .isInstanceOf(HostSandboxException.class)
                .hasMessageContaining("unconfirmed");
        provider.releaseWorktree(child.childWorkspaceId(), true);
        assertThat(Files.exists(childRoot)).isFalse();
    }

    private Fixture fixture(Path workspaceRoot, String workspaceValue, String bindingValue, String locationValue) {
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId(workspaceValue);
        WorkspaceBindingId bindingId = new WorkspaceBindingId(bindingValue);
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef(locationValue);
        locations.register(locationRef, workspaceRoot);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        LocalWorkspaceLocationStore.fingerprintFor(workspaceRoot),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        Workspace workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        return new Fixture(workspaceId, workspaces, bindings, locations);
    }

    private static void copySleepClass(Path target) throws Exception {
        Path destination = target.resolve("io/haifa/agent/sandbox/host/SleepProcess.class");
        Files.createDirectories(destination.getParent());
        try (InputStream input = SleepProcess.class.getResourceAsStream("SleepProcess.class")) {
            Files.copy(java.util.Objects.requireNonNull(input), destination);
        }
    }

    private static String run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        return new String(output, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record Fixture(
            WorkspaceId workspaceId,
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations) {}
}
