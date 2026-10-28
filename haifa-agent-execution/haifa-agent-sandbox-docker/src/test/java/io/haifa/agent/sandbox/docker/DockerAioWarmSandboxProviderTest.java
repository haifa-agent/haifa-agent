package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerAioWarmSandboxProviderTest extends AbstractDockerAioProviderTest {

    @TempDir
    Path workspaceRoot2;

    private WorkspaceId workspaceId2;

    @Override
    protected DockerAioLifecycleMode lifecycleMode() {
        return DockerAioLifecycleMode.WARM_SESSION;
    }

    @BeforeEach
    @Override
    void setUp() {
        super.setUp();
        workspaceId2 = new WorkspaceId("workspace-2");
        registerWorkspace(workspaceId2, workspaceRoot2);
    }

    @Test
    void warmContainerIsReusedAcrossConsecutiveCommands() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            // First execution: triggers cold container creation + start + probe + exec
            SandboxProcessResult result1 = session.execute(execution(ExecutionCommand.shell("echo first")));
            assertThat(result1.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result1.exitCode()).isEqualTo(0);
            assertThat(new String(result1.stdout(), StandardCharsets.UTF_8)).isEqualTo("ok\n");
            assertThat(provider.warmContainerCount()).isEqualTo(1);
            assertThat(engine.containerCreateCount).isEqualTo(1);
            assertThat(engine.containerStartCount).isEqualTo(1);
            assertThat(engine.containerStopCount).isZero();
            assertThat(engine.containerRemoveCount).isZero();
            assertThat(aio.execCount).isEqualTo(1);

            // Second execution on same session: reuses already running container!
            SandboxProcessResult result2 = session.execute(execution(ExecutionCommand.shell("echo second")));
            assertThat(result2.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result2.exitCode()).isEqualTo(0);
            assertThat(provider.warmContainerCount()).isEqualTo(1);
            // Neither container create nor start nor stop occurred on second call
            assertThat(engine.containerCreateCount).isEqualTo(1);
            assertThat(engine.containerStartCount).isEqualTo(1);
            assertThat(engine.containerStopCount).isZero();
            assertThat(engine.containerRemoveCount).isZero();
            assertThat(aio.execCount).isEqualTo(2);
        }

        // Even after session.close(), container remains running in warm pool for the workspace
        assertThat(provider.warmContainerCount()).isEqualTo(1);
        assertThat(engine.containerStopCount).isZero();
        assertThat(engine.containerRemoveCount).isZero();
    }

    @Test
    void subsequentSessionReusesExistingWarmContainer() {
        // Session 1 runs command
        try (SandboxSession session1 = provider.open(profile, workspaceId)) {
            session1.execute(execution(ExecutionCommand.shell("cmd1")));
        }
        assertThat(engine.containerCreateCount).isEqualTo(1);
        assertThat(engine.containerStopCount).isZero();

        // Session 2 opened for the same workspace reuses the existing container
        try (SandboxSession session2 = provider.open(profile, workspaceId)) {
            SandboxProcessResult res2 = session2.execute(execution(ExecutionCommand.shell("cmd2")));
            assertThat(res2.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(engine.containerCreateCount).isEqualTo(1); // still 1
            assertThat(engine.containerStartCount).isEqualTo(1); // still 1
            assertThat(aio.execCount).isEqualTo(2);
        }
    }

    @Test
    void separateWorkspacesGetSeparateWarmContainers() {
        try (SandboxSession session1 = provider.open(profile, workspaceId);
                SandboxSession session2 = provider.open(profile, workspaceId2)) {
            session1.execute(execution(ExecutionCommand.shell("ws1-cmd")));
            session2.execute(execution(workspaceId2, ExecutionCommand.shell("ws2-cmd")));
        }
        assertThat(provider.warmContainerCount()).isEqualTo(2);
        assertThat(engine.containerCreateCount).isEqualTo(2);
        assertThat(engine.networkCreateCount).isEqualTo(2);
    }

    @Test
    void idleReaperEvictsStaleWarmContainers() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            session.execute(execution(ExecutionCommand.shell("echo test")));
        }
        assertThat(provider.warmContainerCount()).isEqualTo(1);

        // Advance time by 10 minutes: not expired yet (idleTimeout is 15 minutes)
        time.advance(Duration.ofMinutes(10));
        provider.reapIdleContainers();
        assertThat(provider.warmContainerCount()).isEqualTo(1);

        // Advance time by 6 more minutes: now 16 minutes > 15 minutes idleTimeout
        time.advance(Duration.ofMinutes(6));
        provider.reapIdleContainers();
        assertThat(provider.warmContainerCount()).isZero();
        assertThat(engine.containerStopCount).isEqualTo(1);
        assertThat(engine.containerRemoveCount).isEqualTo(1);
        assertThat(engine.networkRemoveCount).isEqualTo(1);
    }

    @Test
    void explicitEvictTearsDownContainerImmediately() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            session.execute(execution(ExecutionCommand.shell("echo test")));
        }
        assertThat(provider.warmContainerCount()).isEqualTo(1);

        boolean evicted = provider.evict(workspaceId);
        assertThat(evicted).isTrue();
        assertThat(provider.warmContainerCount()).isZero();
        assertThat(engine.containerStopCount).isEqualTo(1);
        assertThat(engine.containerRemoveCount).isEqualTo(1);
        assertThat(engine.networkRemoveCount).isEqualTo(1);
    }

    @Test
    void providerCloseDestroysAllWarmContainers() {
        try (SandboxSession session1 = provider.open(profile, workspaceId);
                SandboxSession session2 = provider.open(profile, workspaceId2)) {
            session1.execute(execution(ExecutionCommand.shell("echo 1")));
            session2.execute(execution(workspaceId2, ExecutionCommand.shell("echo 2")));
        }
        assertThat(provider.warmContainerCount()).isEqualTo(2);

        provider.close();
        assertThat(provider.warmContainerCount()).isZero();
        assertThat(engine.containerStopCount).isEqualTo(2);
        assertThat(engine.containerRemoveCount).isEqualTo(2);
        assertThat(engine.networkRemoveCount).isEqualTo(2);
    }

    @Test
    void coldStartFailureCleansUpPartialContainerAndNetwork() {
        engine.failVerify = true;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThatThrownBy(() -> session.execute(execution(ExecutionCommand.shell("echo fail"))))
                    .isInstanceOf(SandboxException.class);
        }
        assertThat(provider.warmContainerCount()).isZero();
        assertThat(engine.networkCreateCount).isEqualTo(1);
        assertThat(engine.networkRemoveCount).isEqualTo(1);
        assertThat(engine.containerCreateCount).isEqualTo(1);
        assertThat(engine.containerRemoveCount).isEqualTo(1);
    }

    @Test
    void cancellingInFlightWarmExecutionAbortsImmediatelyAndDestroysContainer() throws Exception {
        aio.execStartedLatch = new CountDownLatch(1);
        aio.execBlockLatch = new CountDownLatch(1);

        try (SandboxSession session = provider.open(profile, workspaceId)) {
            AtomicReference<SandboxProcessResult> resultRef = new AtomicReference<>();
            Thread execThread = new Thread(() -> {
                resultRef.set(session.execute(execution(ExecutionCommand.shell("sleep 100"))));
            });
            execThread.start();

            assertThat(aio.execStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = session.cancel();
            assertThat(cancelled).isTrue();

            execThread.join(5000);
            assertThat(execThread.isAlive()).isFalse();

            SandboxProcessResult result = resultRef.get();
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
            assertThat(result.processTreeTerminated()).isTrue();
            assertThat(provider.warmContainerCount()).isZero();
            assertThat(engine.containerStopCount).isEqualTo(1);
            assertThat(engine.containerRemoveCount).isEqualTo(1);
        }
    }

    @Test
    void cancellingWhileTheContainerIsBeingCreatedRetainsNoWarmContainer() throws Exception {
        aio.readyEntered = new CountDownLatch(1);
        aio.releaseReady = new CountDownLatch(1);

        try (SandboxSession session = provider.open(profile, workspaceId)) {
            AtomicReference<SandboxProcessResult> resultRef = new AtomicReference<>();
            Thread execThread =
                    new Thread(() -> resultRef.set(session.execute(execution(ExecutionCommand.shell("sleep 100")))));
            execThread.start();

            assertThat(aio.readyEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.cancel()).isTrue();
            aio.releaseReady.countDown();
            execThread.join(5000);

            assertThat(execThread.isAlive()).isFalse();
            assertThat(resultRef.get()).isNotNull();
            assertThat(resultRef.get().status()).isEqualTo(SandboxProcessStatus.CANCELLED);
            assertThat(provider.warmContainerCount()).isZero();
            assertThat(engine.containerRemoveCount).isEqualTo(1);
            assertThat(engine.networkRemoveCount).isEqualTo(1);
        }
    }

    @Test
    void concurrentCommandsInSameWorkspaceQueueWithinDeadline() throws Exception {
        aio.execStartedLatch = new CountDownLatch(1);
        aio.execBlockLatch = new CountDownLatch(1);

        try (SandboxSession session1 = provider.open(profile, workspaceId);
                SandboxSession session2 = provider.open(profile, workspaceId)) {
            AtomicReference<SandboxProcessResult> res1 = new AtomicReference<>();
            AtomicReference<SandboxProcessResult> res2 = new AtomicReference<>();

            Thread t1 = new Thread(() -> res1.set(session1.execute(execution(ExecutionCommand.shell("cmd1")))));
            t1.start();

            assertThat(aio.execStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();

            Thread t2 = new Thread(() -> res2.set(session2.execute(execution(ExecutionCommand.shell("cmd2")))));
            t2.start();

            Thread.sleep(100);

            aio.execBlockLatch.countDown();

            t1.join(5000);
            t2.join(5000);

            assertThat(res1.get().status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(res2.get().status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(aio.execCount).isEqualTo(2);
            assertThat(engine.containerCreateCount).isEqualTo(1);
        }
    }

    @Test
    void startupOrphanContainersAndNetworksAreSwept() {
        String orphanContainerName = "haifa-inst1-ws-ws-old";
        String orphanCid = "cid-orphan";
        String orphanNetName = "haifa-inst1-ws-ws-old-net";
        Map<String, String> labels = Map.of(
                DockerAioSandboxProvider.LABEL_INSTALLATION,
                configuration.installationId(),
                DockerAioSandboxProvider.LABEL_ROLE,
                "container");
        engine.containers.put(
                orphanCid,
                new DockerEngine.ContainerInspection(
                        orphanCid,
                        orphanContainerName,
                        "image@sha256:0000000000000000000000000000000000000000000000000000000000000000",
                        orphanNetName,
                        List.of(),
                        labels,
                        Map.of(),
                        true,
                        0,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        false,
                        "",
                        0,
                        ""));
        engine.networks.put(
                orphanNetName, new DockerEngine.NetworkInspection(orphanNetName, "bridge", true, Map.of(), labels));

        try (SandboxSession session = provider.open(profile, workspaceId)) {
            // open triggers sweep
        }

        assertThat(engine.containerStopCount).isEqualTo(1);
        assertThat(engine.containerRemoveCount).isEqualTo(1);
        assertThat(engine.networkRemoveCount).isEqualTo(1);
    }

    @Test
    void timeoutResultSetsProcessTreeTerminatedToFalse() {
        aio.nextStatus = "timed_out";
        aio.nextExitCode = null;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            SandboxProcessResult result = session.execute(execution(ExecutionCommand.shell("sleep 999")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(result.processTreeTerminated()).isFalse();
            assertThat(provider.warmContainerCount()).isEqualTo(1);
        }
    }

    @Test
    void unexpectedKilledMappedToUnknownUnlessCancelled() {
        aio.nextStatus = "killed";
        aio.nextExitCode = null;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            SandboxProcessResult result = session.execute(execution(ExecutionCommand.shell("external-kill")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
        }
    }

    @Test
    void successfulExecutionClosesAioSession() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            SandboxProcessResult result = session.execute(execution(ExecutionCommand.shell("echo ok")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
        }
        assertThat(aio.closeSessionCount).isEqualTo(1);
    }
}
