package io.haifa.agent.sandbox.localnative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionScratchBinding;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
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
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalNativeSandboxProviderTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void preflightBindsProviderAdapterDigestAndEffectiveCapabilities() {
        LocalNativeSandboxConfiguration configuration = configuration();
        LocalNativeSandboxProvider provider = provider(configuration, new PassingAdapter());

        var preflight = provider.preflight(profile(configuration, false));

        assertThat(preflight.providerId()).isEqualTo(LocalNativeSandboxProvider.PROVIDER_ID);
        assertThat(preflight.adapterId()).isEqualTo("fake-contract");
        assertThat(preflight.configurationDigest()).isEqualTo(configuration.digest());
        assertThat(preflight.capabilities()).isEqualTo(new SandboxCapabilities(true, true, true, false, false));
        assertThat(preflight.managedProcessSupported()).isFalse();

        SandboxProfile mismatched = new SandboxProfile(
                new SandboxProfileRef("local-native-test", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("other")),
                Set.of("tool"),
                Set.of(),
                false,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                new SandboxCapabilities(true, true, true, false, false));
        assertThatThrownBy(() -> provider.preflight(mismatched))
                .isInstanceOf(LocalNativeSandboxException.class)
                .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                .isEqualTo("CAPABILITY_UNAVAILABLE");
    }

    @Test
    void opensVerifiedWorkspaceAndRejectsManagedProcessWithoutFallback() throws Exception {
        Path workspaceRoot = temporary.resolve("workspace");
        java.nio.file.Files.createDirectory(workspaceRoot);
        Fixture fixture = fixture(workspaceRoot);
        LocalNativeSandboxConfiguration configuration = configuration();
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                fixture.workspaces(),
                fixture.bindings(),
                fixture.locations(),
                () -> "session-1",
                () -> NOW,
                configuration,
                new PassingAdapter());

        try (var session =
                provider.open(profile(configuration, false), new WorkspaceMount(fixture.workspaceId(), false))) {
            SandboxExecution execution = new SandboxExecution(
                    ExecutionCommand.direct(List.of("tool")),
                    WorkspacePath.root(fixture.workspaceId()),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(1), 64, 64, 1));
            assertThatThrownBy(() -> session.openManagedProcess(execution))
                    .isInstanceOf(LocalNativeSandboxException.class)
                    .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                    .isEqualTo("CAPABILITY_UNAVAILABLE");
        }
        assertThat(java.nio.file.Files.exists(configuration.controlRoot())).isFalse();
    }

    @Test
    void windowsSelectsUnsupportedAdapterAndFailsClosed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isWindows());
        LocalNativeSandboxConfiguration configuration = LocalNativeSandboxConfiguration.defaults();
        LocalNativeSandboxProvider provider = provider(configuration, LocalNativeAdapters.system());

        assertThat(LocalNativeAdapters.system().adapterId()).isEqualTo("unsupported");
        assertThatThrownBy(() -> provider.preflight(profile(configuration, false)))
                .isInstanceOf(LocalNativeSandboxException.class)
                .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                .isEqualTo("SANDBOX_ADAPTER_UNAVAILABLE");
    }

    @Test
    void rejectsHostHomeEvenWhenAProfileAccidentallyAllowsItsName() throws Exception {
        Path workspaceRoot = temporary.resolve("workspace-host-home");
        java.nio.file.Files.createDirectory(workspaceRoot);
        Fixture fixture = fixture(workspaceRoot);
        LocalNativeSandboxConfiguration configuration = configuration();
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                fixture.workspaces(),
                fixture.bindings(),
                fixture.locations(),
                () -> "session-host-home",
                Instant::now,
                configuration,
                new ScratchProbeAdapter());
        SandboxProfile profile = new SandboxProfile(
                new SandboxProfileRef("local-native-host-home-test", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                configuration.digest(),
                Set.of("tool"),
                Set.of("HOME"),
                false,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                new SandboxCapabilities(true, true, true, false, false));

        try (var session = provider.open(profile, new WorkspaceMount(fixture.workspaceId(), false))) {
            var execution = new SandboxExecution(
                    ExecutionCommand.direct(List.of("tool")),
                    WorkspacePath.root(fixture.workspaceId()),
                    Map.of("HOME", temporary.resolve("host-home").toString()),
                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 2));

            assertThatThrownBy(() -> session.execute(execution))
                    .isInstanceOf(LocalNativeSandboxException.class)
                    .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                    .isEqualTo("CAPABILITY_UNAVAILABLE");
        }
    }

    @Test
    void provisionsWritableCodingScratchBindingsAndCleansThemAfterExecution() throws Exception {
        Path workspaceRoot = temporary.resolve("workspace-scratch");
        java.nio.file.Files.createDirectory(workspaceRoot);
        Fixture fixture = fixture(workspaceRoot);
        LocalNativeSandboxConfiguration configuration = configuration();
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                fixture.workspaces(),
                fixture.bindings(),
                fixture.locations(),
                () -> "session-scratch",
                Instant::now,
                configuration,
                new ScratchProbeAdapter());
        var scratch = new ExecutionScratchSpaceSpec(
                true,
                Set.of("TMPDIR", "TMP", "TEMP", "GOTMPDIR"),
                List.of(new ExecutionScratchBinding("GOCACHE", "go-build")));

        try (var session =
                provider.open(profile(configuration, false), new WorkspaceMount(fixture.workspaceId(), false))) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of("tool")),
                    WorkspacePath.root(fixture.workspaceId()),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 2),
                    io.haifa.agent.execution.api.ExecutionInput.none(),
                    scratch));

            assertThat(result.status()).isEqualTo(io.haifa.agent.sandbox.api.SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isZero();
            assertThat(result.scratchProvisioned()).isTrue();
            assertThat(result.scratchCleanupFailed()).isFalse();
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("root-consistent", "root-writable", "go-writable");
        }
        assertThat(configuration.controlRoot()).isDirectory().isEmptyDirectory();
    }

    @Test
    void failsClosedWhenPrivateControlDirectoryCannotBeProvisioned() throws Exception {
        Path workspaceRoot = temporary.resolve("workspace-provision-failure");
        java.nio.file.Files.createDirectory(workspaceRoot);
        Path controlFile = temporary.resolve("control-root-is-a-file");
        java.nio.file.Files.writeString(controlFile, "not a directory");
        Fixture fixture = fixture(workspaceRoot);
        LocalNativeSandboxConfiguration configuration = new LocalNativeSandboxConfiguration(
                List.of("/bin/bash", "-lc"),
                controlFile,
                temporary.resolve("sandbox-exec-provision-failure"),
                temporary.resolve("bwrap-provision-failure"),
                Map.of(),
                Set.of(temporary.resolve("sensitive-provision-failure")));
        LocalNativeSandboxProvider provider = new LocalNativeSandboxProvider(
                fixture.workspaces(),
                fixture.bindings(),
                fixture.locations(),
                () -> "session-provision-failure",
                Instant::now,
                configuration,
                new PassingAdapter());

        try (var session =
                provider.open(profile(configuration, false), new WorkspaceMount(fixture.workspaceId(), false))) {
            assertThatThrownBy(() -> session.execute(new SandboxExecution(
                            ExecutionCommand.direct(List.of("tool")),
                            WorkspacePath.root(fixture.workspaceId()),
                            Map.of(),
                            new ExecutionLimits(Duration.ofSeconds(1), 64, 64, 1))))
                    .isInstanceOf(LocalNativeSandboxException.class)
                    .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                    .isEqualTo("SANDBOX_PROVISION_FAILED");
        }
    }

    private LocalNativeSandboxProvider provider(
            LocalNativeSandboxConfiguration configuration, LocalNativeAdapter adapter) {
        return new LocalNativeSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new LocalWorkspaceLocationStore(),
                () -> "session",
                () -> NOW,
                configuration,
                adapter);
    }

    private LocalNativeSandboxConfiguration configuration() {
        return new LocalNativeSandboxConfiguration(
                List.of("/bin/bash", "-lc"),
                temporary.resolve("controls"),
                temporary.resolve("sandbox-exec"),
                temporary.resolve("bwrap"),
                Map.of(),
                Set.of(temporary.resolve("sensitive")));
    }

    private static SandboxProfile profile(LocalNativeSandboxConfiguration configuration, boolean readOnly) {
        return new SandboxProfile(
                new SandboxProfileRef("local-native-test", "1"),
                LocalNativeSandboxProvider.PROVIDER_ID,
                configuration.digest(),
                Set.of("tool"),
                Set.of(),
                false,
                NetworkPolicy.DENY,
                new SandboxFilesystemPolicy(
                        readOnly ? SandboxWorkspaceAccess.READ_ONLY : SandboxWorkspaceAccess.READ_WRITE,
                        true,
                        Set.of()),
                new SandboxCapabilities(true, true, true, false, false));
    }

    private static Fixture fixture(Path workspaceRoot) {
        InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
        InMemoryWorkspaceBindingStore bindings = new InMemoryWorkspaceBindingStore();
        LocalWorkspaceLocationStore locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-1");
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
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW));
        return new Fixture(workspaceId, workspaces, bindings, locations);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private record Fixture(
            WorkspaceId workspaceId,
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations) {}

    private static final class PassingAdapter implements LocalNativeAdapter {
        @Override
        public String adapterId() {
            return "fake-contract";
        }

        @Override
        public void preflight(LocalNativeSandboxConfiguration configuration) {}

        @Override
        public LocalNativeLaunchPlan prepare(
                LocalNativeSandboxConfiguration configuration,
                SandboxProfile profile,
                Path workspaceRoot,
                Path workingDirectory,
                Path controlDirectory,
                List<LocalNativePathGrant> additionalPaths,
                ExecutionScratchSpaceSpec scratchSpace,
                ExecutionCommand command) {
            throw new AssertionError("managed-process rejection must not prepare a launch");
        }
    }

    private static final class ScratchProbeAdapter implements LocalNativeAdapter {
        @Override
        public String adapterId() {
            return "fake-scratch-probe";
        }

        @Override
        public void preflight(LocalNativeSandboxConfiguration configuration) {}

        @Override
        public LocalNativeLaunchPlan prepare(
                LocalNativeSandboxConfiguration configuration,
                SandboxProfile profile,
                Path workspaceRoot,
                Path workingDirectory,
                Path controlDirectory,
                List<LocalNativePathGrant> additionalPaths,
                ExecutionScratchSpaceSpec scratchSpace,
                ExecutionCommand command) {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            return new LocalNativeLaunchPlan(List.of(
                    javaExecutable,
                    "-cp",
                    System.getProperty("java.class.path"),
                    LocalNativeScratchProbeProcess.class.getName()));
        }
    }
}

final class LocalNativeScratchProbeProcess {
    private LocalNativeScratchProbeProcess() {}

    public static void main(String[] arguments) throws Exception {
        String scratch = requireEnvironment("TMPDIR");
        if (!scratch.equals(requireEnvironment("TMP"))
                || !scratch.equals(requireEnvironment("TEMP"))
                || !scratch.equals(requireEnvironment("GOTMPDIR"))) {
            throw new IllegalStateException("scratch root environment is inconsistent");
        }
        java.nio.file.Files.writeString(Path.of(scratch, "root-probe"), "probe");
        java.nio.file.Files.writeString(Path.of(requireEnvironment("GOCACHE"), "probe"), "probe");
        System.out.print("root-consistent\nroot-writable\ngo-writable\n");
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
