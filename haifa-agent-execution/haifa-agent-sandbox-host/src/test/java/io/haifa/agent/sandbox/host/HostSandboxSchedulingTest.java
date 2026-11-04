package io.haifa.agent.sandbox.host;

import static io.haifa.agent.sandbox.host.HostSandboxIT.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A child JVM fixes the process-wide scheduler before first use; cancellation must progress on one carrier. */
class HostSandboxSchedulingTest {
    @Test
    void aBlockedExecutionObserverDoesNotStarveVirtualControl(@TempDir Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java =
                Path.of(System.getProperty("java.home"), "bin", executable).toString();
        Process child = new ProcessBuilder(
                        java,
                        "-Xmx128m",
                        "-Djdk.virtualThreadScheduler.parallelism=1",
                        "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        HostSandboxSchedulingTest.class.getName(),
                        directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS))
                    .as("execution/control completes")
                    .isTrue();
            assertThat(child.exitValue()).as("physical cancellation result").isZero();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        var fixture = fixture(workspace, "carrier-workspace");
        HostSandboxIT.copySleepClass(workspace);
        var provider = new HostGuardedSandboxProvider(
                fixture.workspaces(),
                fixture.locations(),
                () -> "carrier-session",
                () -> Instant.EPOCH,
                HostShell.auto(),
                root.resolve("scratch"));
        String java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        var profile = SandboxProfile.hostGuarded(
                new SandboxProfileRef("carrier", "1"), provider.configurationDigest(), Set.of(java), Set.of(), false);
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> releaseObserver = new CompletableFuture<>();
        CompletableFuture<SandboxProcessResult> result = new CompletableFuture<>();
        try (var session = provider.open(profile, fixture.workspaceId())) {
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(session.execute(
                            new SandboxExecution(
                                    new ExecutionCommand(
                                            ExecutionCommandMode.DIRECT,
                                            List.of(java, "-cp", ".", SleepProcess.class.getName())),
                                    WorkspacePath.root(fixture.workspaceId()),
                                    Map.of(),
                                    new ExecutionLimits(Duration.ofSeconds(20), 1024, 1024, 2)),
                            new ExecutionOutputObserver() {
                                @Override
                                public void onStarted() {
                                    started.complete(null);
                                    releaseObserver.join();
                                }

                                @Override
                                public void onOutput(ProcessOutputChunk ignored) {}
                            }));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            started.get(5, TimeUnit.SECONDS);
            CompletableFuture<Boolean> control = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                releaseObserver.complete(null);
                try {
                    control.complete(session.cancel());
                } catch (Throwable failure) {
                    control.completeExceptionally(failure);
                }
            });
            try {
                assertThat(control.get(5, TimeUnit.SECONDS)).isTrue();
                SandboxProcessResult finished = result.get(5, TimeUnit.SECONDS);
                assertThat(finished.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
                assertThat(finished.processTreeTerminated()).isTrue();
            } finally {
                // The platform main thread also cleans up the real process when the old implementation starves control.
                releaseObserver.complete(null);
                session.cancel();
            }
        }
    }
}
