package io.haifa.agent.sandbox.localnative;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
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
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LocalNativeOsIsolationSupport {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private LocalNativeOsIsolationSupport() {}

    static void verify(Path temporary, String expectedAdapter) throws Exception {
        Path workspaceRoot = Files.createDirectory(temporary.resolve("workspace"));
        Path sensitiveRoot = Files.createDirectory(temporary.resolve("sensitive"));
        Path sensitiveFile = Files.writeString(sensitiveRoot.resolve("secret.txt"), "must-not-read");
        Files.createSymbolicLink(workspaceRoot.resolve("escape-link"), sensitiveFile);

        Fixture fixture = fixture(workspaceRoot);
        LocalNativeSandboxConfiguration defaults = LocalNativeSandboxConfiguration.defaults();
        LocalNativeSandboxConfiguration configuration = new LocalNativeSandboxConfiguration(
                List.of("/bin/bash", "-lc"),
                temporary.resolve("controls"),
                defaults.seatbeltExecutable(),
                defaults.bubblewrapExecutable(),
                Map.of(),
                Set.of(sensitiveRoot));
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                fixture.workspaces(),
                fixture.bindings(),
                fixture.locations(),
                () -> "session-" + System.nanoTime(),
                () -> Instant.now(),
                configuration);
        SandboxProfile profile = profile(configuration);
        assertThat(provider.preflight(profile).adapterId()).isEqualTo(expectedAdapter);

        try (ServerSocket listener = new ServerSocket(0);
                var session = provider.open(profile, new WorkspaceMount(fixture.workspaceId(), false))) {
            String command =
                    """
                    printf inside > inside.txt
                    test "$(cat inside.txt)" = inside
                    printf boundary-probe >/dev/null || exit 40
                    if cat escape-link >/dev/null 2>&1; then exit 41; fi
                    if (exec 3<>/dev/tcp/127.0.0.1/%d) 2>/dev/null; then exit 42; fi
                    """
                            .formatted(listener.getLocalPort());
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(command),
                    WorkspacePath.root(fixture.workspaceId()),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4)));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode())
                    .as(
                            "sandbox stdout=%s, stderr=%s",
                            new String(result.stdout(), StandardCharsets.UTF_8),
                            new String(result.stderr(), StandardCharsets.UTF_8))
                    .isZero();
            assertThat(new String(result.stderr(), StandardCharsets.UTF_8)).isEmpty();
        }
        assertThat(Files.readString(workspaceRoot.resolve("inside.txt"))).isEqualTo("inside");

        try (var session = provider.open(profile, new WorkspaceMount(fixture.workspaceId(), false))) {
            var timedOut = session.execute(new SandboxExecution(
                    ExecutionCommand.shell("sleep 30 & wait"),
                    WorkspacePath.root(fixture.workspaceId()),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(150), 1024, 1024, 4)));
            assertThat(timedOut.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(timedOut.processTreeTerminated()).isTrue();
        }
        if (Files.exists(configuration.controlRoot())) {
            try (var entries = Files.list(configuration.controlRoot())) {
                assertThat(entries).isEmpty();
            }
        }
    }

    private static SandboxProfile profile(LocalNativeSandboxConfiguration configuration) {
        return new SandboxProfile(
                new SandboxProfileRef("local-native-isolation", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                configuration.digest(),
                Set.of(),
                Set.of(),
                true,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                new SandboxCapabilities(true, true, true, false, false));
    }

    private static Fixture fixture(Path workspaceRoot) {
        InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
        InMemoryWorkspaceBindingStore bindings = new InMemoryWorkspaceBindingStore();
        LocalWorkspaceLocationStore locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId("workspace-isolation");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-isolation");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-isolation");
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
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("project-isolation"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "isolation"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW));
        return new Fixture(workspaceId, workspaces, bindings, locations);
    }

    private record Fixture(
            WorkspaceId workspaceId,
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations) {}
}
