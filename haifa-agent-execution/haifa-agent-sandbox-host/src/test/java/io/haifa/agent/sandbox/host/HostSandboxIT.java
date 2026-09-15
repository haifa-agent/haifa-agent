package io.haifa.agent.sandbox.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionScratchBinding;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostSandboxIT {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @TempDir
    Path isolatedBase;

    @Test
    void runsWhitelistedArgvWithBoundedTimeoutAndHonestCapabilities() throws Exception {
        Fixture fixture = fixture(root, "workspace-1");
        AtomicInteger ids = new AtomicInteger();
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "session-" + ids.incrementAndGet(),
                () -> Instant.ofEpochMilli(System.currentTimeMillis()));
        assertThat(provider.capabilities().processTreeTermination()).isTrue();
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("host-test", "1"),
                provider.configurationDigest(),
                Set.of("java"),
                Set.of(),
                false);
        try (var session = provider.open(profile, fixture.workspaceId)) {
            AtomicInteger dispatches = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<io.haifa.agent.execution.api.ExecutionProcessIdentity>
                    processIdentity = new java.util.concurrent.atomic.AtomicReference<>();
            var version = session.execute(
                    new SandboxExecution(
                            new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("java", "-version")),
                            WorkspacePath.root(fixture.workspaceId),
                            Map.of(),
                            new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)),
                    new io.haifa.agent.execution.api.ExecutionOutputObserver() {
                        @Override
                        public void onStarted(io.haifa.agent.execution.api.ExecutionProcessIdentity identity) {
                            dispatches.incrementAndGet();
                            processIdentity.set(identity);
                        }

                        @Override
                        public void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk ignored) {}
                    });
            assertThat(version.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(version.exitCode()).isZero();
            assertThat(dispatches).hasValue(1);
            assertThat(processIdentity.get().processId()).isPositive();

            try (var managed = session.openManagedProcess(new SandboxExecution(
                    new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("java", "-version")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)))) {
                var managedExit = managed.exit().get(5, TimeUnit.SECONDS);
                assertThat(managedExit.status()).isEqualTo(io.haifa.agent.execution.api.ExecutionStatus.EXITED);
                assertThat(managed.observedProcessCount()).isGreaterThanOrEqualTo(0);
            }

            copySleepClass(root);
            copyProcessClass(root, ProcessTreeParent.class);
            var timeout = session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of("java", "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(100), 1024, 1024, 2)));
            assertThat(timeout.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(timeout.processTreeTerminated()).isTrue();

            var treeTimeout = session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of("java", "-cp", ".", "io.haifa.agent.sandbox.host.ProcessTreeParent", "child.pid")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(500), 1024, 1024, 8)));
            long childPid =
                    Long.parseLong(Files.readString(root.resolve("child.pid")).trim());
            assertThat(ProcessHandle.of(childPid)).isEmpty();
            assertThat(treeTimeout.scratchCleanupFailed()).isFalse();
            assertThat(treeTimeout.processTreeTerminated()).isTrue();
            assertThat(treeTimeout.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);

            var processLimit = session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of(
                                    "java",
                                    "-cp",
                                    ".",
                                    "io.haifa.agent.sandbox.host.ProcessTreeParent",
                                    "limit-child.pid")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 1024, 1024, 1)));
            assertThat(processLimit.status()).isEqualTo(SandboxProcessStatus.PROCESS_LIMIT_EXCEEDED);
            assertThat(processLimit.processTreeTerminated()).isTrue();

            Path unboundedChildPid = root.resolve("unbounded-child.pid");
            try (var managedUnbounded = session.openManagedProcess(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of(
                                    "java",
                                    "-cp",
                                    ".",
                                    "io.haifa.agent.sandbox.host.ProcessTreeParent",
                                    unboundedChildPid.getFileName().toString())),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 1024, 1024)))) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!Files.exists(unboundedChildPid) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(Files.exists(unboundedChildPid)).isTrue();
                assertThat(managedUnbounded.exit().isDone()).isFalse();
                assertThat(managedUnbounded.cancel()).isTrue();
                var cancelledUnbounded = managedUnbounded.exit().get(5, TimeUnit.SECONDS);
                assertThat(cancelledUnbounded.status())
                        .isEqualTo(io.haifa.agent.execution.api.ExecutionStatus.CANCELLED);
                long unboundedChildPidValue =
                        Long.parseLong(Files.readString(unboundedChildPid).trim());
                assertThat(ProcessHandle.of(unboundedChildPidValue).filter(ProcessHandle::isAlive))
                        .isEmpty();
            }

            try (var managed = session.openManagedProcess(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of("java", "-cp", ".", "io.haifa.agent.sandbox.host.SleepProcess")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 1024, 1024, 2)))) {
                assertThat(managed.cancel()).isTrue();
                assertThat(managed.exit().get(5, TimeUnit.SECONDS).status())
                        .isEqualTo(io.haifa.agent.execution.api.ExecutionStatus.CANCELLED);
            }

            Path cancelledChildPid = root.resolve("cancel-child.pid");
            var cancelled = CompletableFuture.supplyAsync(() -> session.execute(new SandboxExecution(
                    new ExecutionCommand(
                            ExecutionCommandMode.DIRECT,
                            List.of(
                                    "java",
                                    "-cp",
                                    ".",
                                    "io.haifa.agent.sandbox.host.ProcessTreeParent",
                                    cancelledChildPid.getFileName().toString())),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 1024, 1024, 8))));
            long cancelledChild = Long.parseLong(waitForText(cancelledChildPid));
            assertThat(session.cancel()).isTrue();
            var cancelledResult = cancelled.get(5, TimeUnit.SECONDS);
            assertThat(cancelledResult.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
            assertThat(cancelledResult.processTreeTerminated()).isTrue();
            assertThat(ProcessHandle.of(cancelledChild)).isEmpty();
        }
    }

    private static String waitForText(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                String value = Files.readString(path).trim();
                if (!value.isEmpty()) return value;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for process identity file: " + path.getFileName());
    }

    @Test
    void runsGeneralShellTextThroughConfiguredShellAndStreamsBoundedHeadAndTail() throws Exception {
        Fixture fixture = fixture(root, "workspace-shell");
        HostShell shell = HostShell.auto();
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "shell-session", Instant::now, shell);
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("shell-test", "1"),
                provider.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);
        String command = isWindows()
                ? "$value = 'shell-ok'; $value | Set-Content result.txt; Get-Content result.txt"
                : "printf 'shell-ok\\n' | tr a-z A-Z > result.txt; cat result.txt";
        var streamed = new java.io.ByteArrayOutputStream();

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(
                    new SandboxExecution(
                            ExecutionCommand.shell(command),
                            WorkspacePath.root(fixture.workspaceId),
                            hostBaselineEnvironment(),
                            new ExecutionLimits(Duration.ofSeconds(10), 64, 4096, 4)),
                    chunk -> streamed.writeBytes(chunk.bytes()));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(root.resolve("result.txt"))).containsIgnoringCase("shell-ok");
            assertThat(new String(streamed.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                    .containsIgnoringCase("shell-ok");
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n"))
                    .containsIgnoringCase("shell-ok");
        }

        SandboxProfile secretProfile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("secret-test", "1"),
                provider.configurationDigest(),
                Set.of(),
                Set.of("DEEPSEEK_API_KEY"),
                true);
        try (var session = provider.open(secretProfile, fixture.workspaceId)) {
            assertThatThrownBy(() -> session.execute(new SandboxExecution(
                            ExecutionCommand.shell(command),
                            WorkspacePath.root(fixture.workspaceId),
                            Map.of("DEEPSEEK_API_KEY", "must-not-be-inherited"),
                            new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4))))
                    .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                            .isEqualTo("ENVIRONMENT_DENIED"));
        }

        SandboxProfile shellDenied = SandboxProfile.hostGuarded(
                new SandboxProfileRef("shell-denied", "1"), provider.configurationDigest(), Set.of(), Set.of(), false);
        try (var session = provider.open(shellDenied, fixture.workspaceId)) {
            assertThatThrownBy(() -> session.execute(new SandboxExecution(
                            ExecutionCommand.shell(command),
                            WorkspacePath.root(fixture.workspaceId),
                            Map.of(),
                            new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4))))
                    .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                            .isEqualTo("SHELL_DENIED"));
        }

        try (var session = provider.open(profile, fixture.workspaceId)) {
            assertThat(session.cancel()).isTrue();
            var cancelledBeforeStart = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(command),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4)));
            assertThat(cancelledBeforeStart.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
        }

        try (var session = provider.open(profile, fixture.workspaceId)) {
            assertThatThrownBy(() -> session.openManagedProcess(new SandboxExecution(
                            ExecutionCommand.shell(command),
                            WorkspacePath.root(fixture.workspaceId),
                            Map.of(),
                            new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4))))
                    .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                            .isEqualTo("MANAGED_SHELL_DENIED"));
        }
    }

    @Test
    void writesBoundedInitialInputThenClosesStdin() throws Exception {
        Fixture fixture = fixture(root, "workspace-stdin");
        copyProcessClass(root, StdinEchoProcess.class);
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "stdin-session", Instant::now);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("stdin-test", "1"),
                provider.configurationDigest(),
                Set.of(javaExecutable),
                Set.of(),
                false);
        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of(javaExecutable, "-cp", ".", StdinEchoProcess.class.getName())),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2),
                    ExecutionInput.utf8("script-through-stdin")));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("script-through-stdin");
        }
    }

    @Test
    void terminatesInspectionWhenItsOutputBudgetIsExceeded() throws Exception {
        Fixture fixture = fixture(root, "workspace-output-limit");
        copyProcessClass(root, LargeOutputProcess.class);
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "output-limit-session",
                Instant::now,
                HostShell.auto(),
                isolatedBase.resolve("output-limit-scratch"));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("output-limit-test", "1"),
                provider.configurationDigest(),
                Set.of(javaExecutable),
                Set.of(),
                false);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of(javaExecutable, "-cp", ".", LargeOutputProcess.class.getName())),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(
                            Duration.ofSeconds(10),
                            1024,
                            1024,
                            2,
                            io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE)));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.OUTPUT_LIMIT_EXCEEDED);
            assertThat(result.processTreeTerminated()).isTrue();
            assertThat(result.stdoutTruncated()).isTrue();
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .startsWith("BEGIN-")
                    .contains("bytes omitted");
        }
    }

    @Test
    void supportsTemporaryLoopbackServerRoundTripWithinOneCommand() throws Exception {
        Fixture fixture = fixture(root, "workspace-loopback");
        copyProcessClass(root, LoopbackRoundTripProcess.class);
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "loopback-session", Instant::now);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("loopback-test", "1"),
                provider.configurationDigest(),
                Set.of(javaExecutable),
                Set.of(),
                false);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(
                            List.of(javaExecutable, "-cp", ".", LoopbackRoundTripProcess.class.getName())),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isZero();
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("loopback-round-trip-ok");
        }
    }

    @Test
    void compilesAndRunsWorkspaceProgramWithTheHostToolchain() throws Exception {
        Fixture fixture = fixture(root, "workspace-build");
        Files.writeString(
                root.resolve("Baseline.java"),
                "public class Baseline { public static void main(String[] args) { "
                        + "System.out.print(\"compile-test-ok\"); } }");
        String executableSuffix = isWindows() ? ".exe" : "";
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java" + executableSuffix)
                .toString();
        String javacExecutable = Path.of(System.getProperty("java.home"), "bin", "javac" + executableSuffix)
                .toString();
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "build-session", Instant::now);
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("build-test", "1"),
                provider.configurationDigest(),
                Set.of(javaExecutable, javacExecutable),
                Set.of(),
                false);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var compilation = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of(javacExecutable, "Baseline.java")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)));
            assertThat(compilation.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(compilation.exitCode()).isZero();

            var testRun = session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of(javaExecutable, "-cp", ".", "Baseline")),
                    WorkspacePath.root(fixture.workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 2)));
            assertThat(testRun.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(testRun.exitCode()).isZero();
            assertThat(new String(testRun.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("compile-test-ok");
        }
    }

    @Test
    void reportsReusableRealWorkspaceAndParentPaths() throws Exception {
        Path workspaceRoot = Files.createDirectories(root.resolve("workspace path 空格"));
        Fixture fixture = fixture(workspaceRoot, "workspace-path");
        HostShell shell = HostShell.auto();
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "path-session", Instant::now, shell);
        SandboxProfile profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("path-test", "1"),
                provider.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);
        String command =
                isWindows() ? "(Get-Location).Path; Set-Location ..; (Get-Location).Path" : "pwd -P; cd ..; pwd -P";

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(command),
                    WorkspacePath.root(fixture.workspaceId),
                    hostBaselineEnvironment(),
                    new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 4)));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isZero();
            List<String> paths = new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
            assertThat(paths).hasSize(2).noneMatch(path -> path.contains("<workspace>"));
            assertThat(Path.of(paths.get(0)).toRealPath()).isEqualTo(workspaceRoot.toRealPath());
            assertThat(Path.of(paths.get(1)).toRealPath())
                    .isEqualTo(workspaceRoot.getParent().toRealPath());
        }
    }

    @Test
    void injectsPrivateWritableScratchAndCleansItWithoutClaimingIsolation() throws Exception {
        Fixture fixture = fixture(root, "workspace-scratch");
        Path scratchRoot = isolatedBase.resolve("host-scratch");
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "scratch-session",
                Instant::now,
                HostShell.auto(),
                scratchRoot);
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("scratch-test", "1"),
                provider.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);
        var scratch = new ExecutionScratchSpaceSpec(
                Set.of("TMPDIR", "TMP", "TEMP", "GOTMPDIR"),
                List.of(new ExecutionScratchBinding("GOCACHE", "go-build")));

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(scratchProbeCommand()),
                    WorkspacePath.root(fixture.workspaceId),
                    hostBaselineEnvironment(),
                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 4),
                    ExecutionInput.none(),
                    scratch));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isZero();
            assertThat(result.scratchProvisioned()).isTrue();
            assertThat(result.scratchCleanupFailed()).isFalse();
            assertThat(new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("scratch-ok");
        }
        assertThat(scratchRoot).isDirectory().isEmptyDirectory();
    }

    @Test
    void omitsScratchProvisioningWhenScratchSpecIsNone() throws Exception {
        Fixture fixture = fixture(root, "workspace-no-scratch");
        Path scratchRoot = isolatedBase.resolve("host-no-scratch");
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "no-scratch-session",
                Instant::now,
                HostShell.auto(),
                scratchRoot);
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("no-scratch-test", "1"),
                provider.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);

        String probeFile = "haifa-shared-tmp-probe-" + System.currentTimeMillis() + ".txt";
        Path probePath = null;
        try {
            String writeCommand = isWindows()
                    ? "$p = (Join-Path $env:TEMP '" + probeFile
                            + "'); [IO.File]::WriteAllText($p, 'persisted-tmp'); [Console]::Out.Write($p)"
                    : "p=\"${TMPDIR:-/tmp}/" + probeFile
                            + "\" && printf persisted-tmp > \"$p\" && printf \"%s\" \"$p\"";

            try (var session1 = provider.open(profile, fixture.workspaceId)) {
                var step1 = session1.execute(new SandboxExecution(
                        ExecutionCommand.shell(writeCommand),
                        WorkspacePath.root(fixture.workspaceId),
                        hostBaselineEnvironment(),
                        new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096),
                        ExecutionInput.none(),
                        ExecutionScratchSpaceSpec.none()));

                assertThat(step1.status()).isEqualTo(SandboxProcessStatus.EXITED);
                assertThat(step1.exitCode()).isZero();
                assertThat(step1.scratchProvisioned()).isFalse();
                assertThat(step1.scratchCleanupFailed()).isFalse();
                String pathOutput = new String(step1.stdout(), java.nio.charset.StandardCharsets.UTF_8).trim();
                assertThat(pathOutput).isNotEmpty();
                probePath = Path.of(pathOutput).toAbsolutePath().normalize();
            }

            assertThat(Files.isRegularFile(probePath)).isTrue();

            String readCommand = isWindows()
                    ? "[IO.File]::ReadAllText((Join-Path $env:TEMP '" + probeFile + "'))"
                    : "cat \"${TMPDIR:-/tmp}/" + probeFile + "\"";

            try (var session2 = provider.open(profile, fixture.workspaceId)) {
                var step2 = session2.execute(new SandboxExecution(
                        ExecutionCommand.shell(readCommand),
                        WorkspacePath.root(fixture.workspaceId),
                        hostBaselineEnvironment(),
                        new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096),
                        ExecutionInput.none(),
                        ExecutionScratchSpaceSpec.none()));

                assertThat(step2.status()).isEqualTo(SandboxProcessStatus.EXITED);
                assertThat(step2.exitCode()).isZero();
                assertThat(step2.scratchProvisioned()).isFalse();
                assertThat(new String(step2.stdout(), java.nio.charset.StandardCharsets.UTF_8).trim())
                        .isEqualTo("persisted-tmp");
            }
        } finally {
            if (probePath != null) {
                Files.deleteIfExists(probePath);
            }
        }
        assertThat(scratchRoot).doesNotExist();
    }

    @Test
    void rejectsHomeSystemRootAndWorkspaceOverlappingScratchRoots() throws Exception {
        Fixture fixture = fixture(root, "workspace-unsafe-scratch");
        assertThatThrownBy(() -> new HostGuardedSandboxProvider(
                        fixture.workspaces,
                        fixture.locations,
                        () -> "unsafe-home",
                        Instant::now,
                        HostShell.auto(),
                        Path.of(System.getProperty("user.home"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HostGuardedSandboxProvider(
                        fixture.workspaces,
                        fixture.locations,
                        () -> "unsafe-root",
                        Instant::now,
                        HostShell.auto(),
                        root.getRoot()))
                .isInstanceOf(IllegalArgumentException.class);

        var overlapsWorkspace = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "unsafe-workspace",
                Instant::now,
                HostShell.auto(),
                root.resolve("scratch"));
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("unsafe-workspace", "1"),
                overlapsWorkspace.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);
        try (var session = overlapsWorkspace.open(profile, fixture.workspaceId)) {
            var ok = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(isWindows() ? "[Console]::Out.Write('ok')" : "printf ok"),
                    WorkspacePath.root(fixture.workspaceId),
                    hostBaselineEnvironment(),
                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096),
                    ExecutionInput.none(),
                    ExecutionScratchSpaceSpec.none()));
            assertThat(ok.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(ok.exitCode()).isZero();

            assertThatThrownBy(() -> session.execute(new SandboxExecution(
                            ExecutionCommand.shell(isWindows() ? "[Console]::Out.Write('fail')" : "printf fail"),
                            WorkspacePath.root(fixture.workspaceId),
                            hostBaselineEnvironment(),
                            new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096),
                            ExecutionInput.none(),
                            ExecutionScratchSpaceSpec.genericRequired())))
                    .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                            .isEqualTo("SCRATCH_PROVISION_FAILED"));
        }
    }

    @Test
    void failsClosedWhenScratchCannotBeProvisioned() throws Exception {
        Fixture fixture = fixture(root, "workspace-scratch-failure");
        Path scratchRoot = isolatedBase.resolve("scratch-root-is-a-file");
        Files.writeString(scratchRoot, "not a directory");
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "scratch-provision-failure",
                Instant::now,
                HostShell.auto(),
                scratchRoot);
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("scratch-provision-failure", "1"),
                provider.configurationDigest(),
                Set.of("/bin/sh"),
                Set.of(),
                false);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            AtomicInteger dispatches = new AtomicInteger();
            assertThatThrownBy(() -> session.execute(
                            new SandboxExecution(
                                    ExecutionCommand.direct(List.of("/bin/sh", "-c", "true")),
                                    WorkspacePath.root(fixture.workspaceId),
                                    Map.of(),
                                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 2),
                                    ExecutionInput.none(),
                                    ExecutionScratchSpaceSpec.genericRequired()),
                            new io.haifa.agent.execution.api.ExecutionOutputObserver() {
                                @Override
                                public void onStarted() {
                                    dispatches.incrementAndGet();
                                }

                                @Override
                                public void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk ignored) {}
                            }))
                    .isInstanceOfSatisfying(HostSandboxException.class, exception -> assertThat(exception.code())
                            .isEqualTo("SCRATCH_PROVISION_FAILED"));
            assertThat(dispatches).hasValue(0);
        }
    }

    @Test
    void keepsProcessStartFailureUnknownWithoutDispatch() throws Exception {
        Fixture fixture = fixture(root, "workspace-start-failure");
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces, fixture.locations, () -> "process-start-failure", Instant::now);
        String missingExecutable = "haifa-definitely-missing-executable";
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("process-start-failure", "1"),
                provider.configurationDigest(),
                Set.of(missingExecutable),
                Set.of(),
                false);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            AtomicInteger dispatches = new AtomicInteger();
            SandboxProcessResult result = session.execute(
                    new SandboxExecution(
                            ExecutionCommand.direct(List.of(missingExecutable)),
                            WorkspacePath.root(fixture.workspaceId),
                            Map.of(),
                            new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 2)),
                    new io.haifa.agent.execution.api.ExecutionOutputObserver() {
                        @Override
                        public void onStarted() {
                            dispatches.incrementAndGet();
                        }

                        @Override
                        public void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk ignored) {}
                    });

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
            assertThat(result.outputIncomplete()).isTrue();
            assertThat(dispatches).hasValue(0);
        }
    }

    @Test
    void keepsTimedOutTreeTerminationUnconfirmed() {
        assertThat(HostGuardedSandboxProvider.settledStatus(HostGuardedSandboxProvider.WaitOutcome.TIMED_OUT, false))
                .isEqualTo(SandboxProcessStatus.UNKNOWN);
        assertThat(HostGuardedSandboxProvider.settlementFailureCode(
                        HostGuardedSandboxProvider.WaitOutcome.TIMED_OUT, false))
                .isEqualTo("TIMEOUT_TREE_UNCONFIRMED");
    }

    @Test
    void recordsOutputReadFailureAsIncompleteDrain() {
        InputStream failedInput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated output read failure");
            }
        };

        var output = HostGuardedSandboxProvider.read(
                failedInput,
                4096,
                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                io.haifa.agent.execution.api.ExecutionOutputObserver.noop(),
                io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL,
                new java.util.concurrent.atomic.AtomicBoolean());

        assertThat(output.truncated()).isTrue();
        assertThat(output.complete()).isFalse();
    }

    @Test
    void reportsScratchCleanupFailureWithoutExposingItsPhysicalPath() throws Exception {
        Fixture fixture = fixture(root, "workspace-cleanup-failure");
        Path scratchRoot = isolatedBase.resolve("host-cleanup-failure");
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces,
                fixture.locations,
                () -> "scratch-cleanup-failure",
                Instant::now,
                HostShell.auto(),
                scratchRoot,
                target -> {
                    throw new IOException("simulated scratch cleanup failure");
                });
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("scratch-cleanup-failure", "1"),
                provider.configurationDigest(),
                Set.of(),
                hostBaselineEnvironment().keySet(),
                true);

        try (var session = provider.open(profile, fixture.workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.shell(
                            isWindows() ? "[Console]::Out.Write('cleanup-probe')" : "printf cleanup-probe"),
                    WorkspacePath.root(fixture.workspaceId),
                    hostBaselineEnvironment(),
                    new ExecutionLimits(Duration.ofSeconds(5), 4096, 4096, 2),
                    ExecutionInput.none(),
                    ExecutionScratchSpaceSpec.genericRequired()));

            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
            assertThat(result.scratchProvisioned()).isTrue();
            assertThat(result.scratchCleanupFailed()).isTrue();
        } finally {
            if (Files.isDirectory(scratchRoot)) {
                try (var sessions = Files.list(scratchRoot)) {
                    for (Path directory : sessions.toList()) {
                        try (var paths = Files.walk(directory)) {
                            for (Path path : paths.sorted(java.util.Comparator.reverseOrder())
                                    .toList()) {
                                Files.deleteIfExists(path);
                            }
                        }
                    }
                }
            }
        }
        assertThat(scratchRoot).isDirectory().isEmptyDirectory();
    }

    private Fixture fixture(Path workspaceRoot, String workspaceValue) {
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId(workspaceValue);
        locations.register(workspaceId, workspaceRoot);
        Workspace workspace = Workspace.provision(
                        workspaceId, new ProjectId("project-1"), WorkspaceRevision.initial("test"), NOW)
                .activate(NOW);
        workspaces.create(workspace);
        return new Fixture(workspaceId, workspaces, locations);
    }

    private static void copySleepClass(Path target) throws Exception {
        copyProcessClass(target, SleepProcess.class);
    }

    private static void copyProcessClass(Path target, Class<?> type) throws Exception {
        Path destination = target.resolve(type.getName().replace('.', '/') + ".class");
        Files.createDirectories(destination.getParent());
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static Map<String, String> hostBaselineEnvironment() {
        if (!isWindows()) return Map.of();
        var environment = new LinkedHashMap<String, String>();
        for (String name : List.of(
                "PATH",
                "PATHEXT",
                "SystemRoot",
                "SystemDrive",
                "WINDIR",
                "ComSpec",
                "USERPROFILE",
                "HOMEDRIVE",
                "HOMEPATH",
                "APPDATA",
                "LOCALAPPDATA",
                "ProgramData",
                "ProgramFiles",
                "ProgramW6432",
                "PUBLIC",
                "PSModulePath",
                "TMP",
                "TEMP",
                "JAVA_HOME")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) environment.put(name, value);
        }
        return Map.copyOf(environment);
    }

    private static String scratchProbeCommand() {
        if (isWindows()) {
            return "if ($env:TMPDIR -ne $env:TMP -or $env:TMP -ne $env:TEMP) { exit 21 }; "
                    + "if ($env:GOTMPDIR -ne $env:TMPDIR) { exit 22 }; "
                    + "[IO.File]::WriteAllText((Join-Path $env:GOCACHE 'probe'), 'probe'); "
                    + "[Console]::Out.Write('scratch-ok')";
        }
        return "test \"$TMPDIR\" = \"$TMP\" && test \"$TMP\" = \"$TEMP\"; "
                + "test \"$GOTMPDIR\" = \"$TMPDIR\"; "
                + "test -w \"$TMPDIR\" && test -w \"$GOCACHE\"; "
                + "touch \"$GOCACHE/probe\"; printf scratch-ok";
    }

    private record Fixture(
            WorkspaceId workspaceId, InMemoryWorkspaceStore workspaces, HostWorkspaceLocationStore locations) {}
}
