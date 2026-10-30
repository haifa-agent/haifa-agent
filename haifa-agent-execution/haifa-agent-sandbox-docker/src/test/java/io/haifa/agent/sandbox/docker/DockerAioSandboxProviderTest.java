package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DockerAioSandboxProviderTest extends AbstractDockerAioProviderTest {

    @Override
    protected DockerAioLifecycleMode lifecycleMode() {
        return DockerAioLifecycleMode.EPHEMERAL;
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void desktopRoutesBothLifecyclesThroughThePublishedLoopbackPort() {
        for (var mode : DockerAioLifecycleMode.values()) {
            var config = defaultConfiguration(
                    mode,
                    8,
                    Duration.ofMinutes(15),
                    DockerAioSandboxConfiguration.localDockerSocket("Windows 11", null));
            aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
            try (var desktop = createProvider(config, () -> "session-1");
                    var session = desktop.open(createProfile(config), workspaceId)) {
                var result = session.execute(execution(ExecutionCommand.shell("printf hello")));
                assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
                assertThat(aio.lastUri).isEqualTo(java.net.URI.create("http://127.0.0.1:32768"));
                // Ephemeral mode tears the container down before execute() returns, so the fake
                // clears `inspection`; the container identity assertion reads the recorded one.
                assertThat(engine.lastInspection.containerIp()).isNotBlank();
            }
        }
    }

    @Test
    void openOnlyAllocatesLocalIdentity() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThat(session.id().value()).isEqualTo("session-1");
            assertThat(engine.calls).isEmpty();
            assertThat(aio.execCount).isZero();
        }
    }

    @Test
    void executeRunsCreateReadyExecAndVerifiedTeardown() {
        aio.result = new AioClient.ExecResult(
                "aio-session-1",
                "command-1",
                "completed",
                7,
                "stdout".getBytes(StandardCharsets.UTF_8),
                "stderr".getBytes(StandardCharsets.UTF_8),
                false,
                false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("printf hello")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isEqualTo(7);
            assertThat(new String(result.stdout(), StandardCharsets.UTF_8)).isEqualTo("stdout");
            assertThat(new String(result.stderr(), StandardCharsets.UTF_8)).isEqualTo("stderr");
            assertThat(result.processTreeTerminated()).isTrue();
            assertThat(result.scratchProvisioned()).isFalse();
            assertThat(aio.lastRequest.command()).isEqualTo("printf hello");
            assertThat(aio.lastRequest.execDirectory()).isEqualTo("/mnt/user-data/workspace");
            assertThat(aio.lastRequest.environment()).isEmpty();
            assertThat(engine.calls)
                    .containsSubsequence(
                            "image",
                            "network-create",
                            "network-inspect",
                            "container-create",
                            "container-start",
                            "container-inspect",
                            "container-stop",
                            "container-remove",
                            "container-inspect",
                            "network-remove",
                            "network-inspect");
        }
    }

    @Test
    void scriptInputIsPipedWithoutHostStdoutLeak() {
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            session.execute(new SandboxExecution(
                    ExecutionCommand.direct(List.of("/bin/bash", "-s", "--")),
                    WorkspacePath.root(workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofSeconds(5), 1024, 1024),
                    ExecutionInput.utf8("echo from-stdin")));
            assertThat(aio.lastRequest.command())
                    .startsWith("printf %s '")
                    .contains("| base64 -d | exec '/bin/bash' '-s' '--'");
        }
    }

    @Test
    void outputOverTheByteBudgetIsBoundedAndMarked() {
        aio.result = new AioClient.ExecResult(
                "aio",
                "cmd",
                "completed",
                0,
                "x".repeat(2_000).getBytes(StandardCharsets.UTF_8),
                new byte[0],
                false,
                false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("true")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.stdoutTruncated()).isTrue();
            assertThat(result.stdout().length).isLessThanOrEqualTo(1024);
            assertThat(new String(result.stdout(), StandardCharsets.UTF_8)).contains("bytes omitted");
        }
    }

    @Test
    void timeoutBeforeDispatchIsBoundedAndTearsDown() {
        aio.ready = false;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("sleep 10")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(result.exitCode()).isNull();
            assertThat(result.processTreeTerminated()).isTrue();
            assertThat(aio.execCount).isZero();
            assertThat(engine.calls).contains("container-stop", "container-remove", "network-remove");
        }
    }

    @Test
    void cancelBeforeExecuteDoesNotCreateAnything() {
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThat(session.cancel()).isTrue();
            var result = session.execute(execution(ExecutionCommand.shell("echo never")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
            assertThat(aio.execCount).isZero();
            assertThat(engine.calls).isEmpty();
        }
    }

    @Test
    void cancelWhileWaitingForReadyStopsWithoutExecuting() throws Exception {
        aio.readyEntered = new CountDownLatch(1);
        aio.releaseReady = new CountDownLatch(1);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            Thread execution =
                    Thread.ofVirtual().start(() -> session.execute(execution(ExecutionCommand.shell("true"))));
            assertThat(aio.readyEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.cancel()).isTrue();
            aio.releaseReady.countDown();
            execution.join(5_000);
            assertThat(execution.isAlive()).isFalse();
            assertThat(aio.execCount).isZero();
            assertThat(engine.calls).contains("container-stop", "container-remove", "network-remove");
        } finally {
            aio.releaseReady.countDown();
        }
    }

    @Test
    void unconfirmedTeardownIsUnknownEvenWhenCommandExitedZero() {
        engine.keepContainerAfterRemove = true;
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("true")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
            assertThat(result.exitCode()).isNull();
            assertThat(result.processTreeTerminated()).isFalse();
            assertThat(result.failureCode()).isEqualTo("CONTAINER_TEARDOWN_UNCONFIRMED");
        }
    }

    @Test
    void closeRetriesAnUnconfirmedTeardown() {
        engine.keepContainerAfterRemove = true;
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThat(session.execute(execution(ExecutionCommand.shell("true")))
                            .status())
                    .isEqualTo(SandboxProcessStatus.UNKNOWN);
            engine.keepContainerAfterRemove = false;
            session.close();
            assertThat(engine.calls.stream().filter("container-remove"::equals).count())
                    .isGreaterThanOrEqualTo(2);
            assertThat(engine.inspectContainer("container-1")).isNull();
            assertThat(engine.inspectNetwork(engine.networkName)).isNull();
        }
    }

    @Test
    void lostCreateResponseIsReconciledByExactIdentityAndNotExecuted() {
        engine.lostCreateResponse = true;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThatThrownBy(() -> session.execute(execution(ExecutionCommand.shell("echo never"))))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("creation result is unknown");
            assertThat(aio.execCount).isZero();
            assertThat(engine.calls).contains("container-remove", "network-remove");
        }
    }

    @Test
    void identityDriftFailsBeforeDispatch() {
        engine.driftLabels = true;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            assertThatThrownBy(() -> session.execute(execution(ExecutionCommand.shell("echo never"))))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("identity or isolation drifted");
            assertThat(aio.execCount).isZero();
            assertThat(engine.calls).contains("container-remove", "network-remove");
        }
    }

    @Test
    void secondExecuteIsRejected() {
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            session.execute(execution(ExecutionCommand.shell("true")));
            assertThatThrownBy(() -> session.execute(execution(ExecutionCommand.shell("true"))))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("already executed");
        }
    }

    @Test
    void inspectFailureDuringNetworkTeardownLeavesTeardownUnconfirmed() {
        engine.failInspectNetworkDuringTeardown = true;
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("true")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
            assertThat(result.processTreeTerminated()).isFalse();
            assertThat(result.failureCode()).isEqualTo("CONTAINER_TEARDOWN_UNCONFIRMED");
        }
    }

    @Test
    void inspectFailureDuringContainerTeardownLeavesTeardownUnconfirmed() {
        engine.failInspectContainerDuringTeardown = true;
        aio.result = new AioClient.ExecResult("aio", "cmd", "completed", 0, new byte[0], new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("true")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.UNKNOWN);
            assertThat(result.processTreeTerminated()).isFalse();
            assertThat(result.failureCode()).isEqualTo("CONTAINER_TEARDOWN_UNCONFIRMED");
        }
    }

    @Test
    void teardownDuringCreationInFlightDoesNotCacheConfirmedTeardown() throws Exception {
        engine.createEntered = new CountDownLatch(1);
        engine.releaseCreate = new CountDownLatch(1);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            Thread execution =
                    Thread.ofVirtual().start(() -> session.execute(execution(ExecutionCommand.shell("true"))));
            assertThat(engine.createEntered.await(5, TimeUnit.SECONDS)).isTrue();
            session.close();
            engine.releaseCreate.countDown();
            execution.join(5_000);
            assertThat(execution.isAlive()).isFalse();
            assertThat(engine.calls).contains("container-remove");
            assertThat(engine.inspection).isNull();
        } finally {
            engine.releaseCreate.countDown();
        }
    }

    @Test
    void connectsDirectlyToContainerIpWhenPortMappingIsZero() {
        engine.managementPort = 0;
        engine.containerIp = "172.18.0.5";
        aio.result = new AioClient.ExecResult(
                "session-1", "cmd-1", "completed", 0, "ok".getBytes(StandardCharsets.UTF_8), new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("echo hello")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(engine.calls).contains("container-start", "container-remove");
        }
    }

    @Test
    void prefersContainerIpOverManagementPortForInternalNetworkRouting() {
        engine.managementPort = 32768;
        engine.containerIp = "172.18.0.5";
        aio.result = new AioClient.ExecResult(
                "session-1", "cmd-1", "completed", 0, "ok".getBytes(StandardCharsets.UTF_8), new byte[0], false, false);
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(execution(ExecutionCommand.shell("echo hello")));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.EXITED);
            assertThat(result.exitCode()).isEqualTo(0);
        }
    }

    @Test
    void executionTimesOutEarlyWhenSetupExceedsBudget() {
        engine.ensureImageDelay = true;
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var result = session.execute(new SandboxExecution(
                    ExecutionCommand.shell("echo fast"),
                    WorkspacePath.root(workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(100), 1024, 1024)));
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(result.failureCode()).isEqualTo("TIMEOUT");
            assertThat(engine.calls).containsExactly("image");
            assertThat(engine.calls).doesNotContain("network-create", "container-create", "container-start");
        }
    }

    @Test
    void cancelledInFlightExecutionBecomesCancelledStatus() throws Exception {
        aio.execEntered = new CountDownLatch(1);
        aio.releaseExec = new CountDownLatch(1);
        aio.execException =
                new AioClient.AioProtocolException("AIO_TRANSPORT_ERROR", "socket closed during cancellation");
        try (SandboxSession session = provider.open(profile, workspaceId)) {
            var future =
                    CompletableFuture.supplyAsync(() -> session.execute(execution(ExecutionCommand.shell("sleep 10"))));
            assertThat(aio.execEntered.await(2, TimeUnit.SECONDS)).isTrue();
            boolean cancelled = session.cancel();
            assertThat(cancelled).isTrue();
            aio.releaseExec.countDown();
            var result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(SandboxProcessStatus.CANCELLED);
            assertThat(result.failureCode()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void concurrentExecutionsAreBoundedByMaxConcurrentSessions() throws Exception {
        var limitedConfig = defaultConfiguration(lifecycleMode(), 1, Duration.ofMinutes(15));
        var limitedProvider = createProvider(limitedConfig, () -> "session-limited");
        var limitedProfile = createProfile(limitedConfig);

        aio.execEntered = new CountDownLatch(1);
        aio.releaseExec = new CountDownLatch(1);
        aio.result = new AioClient.ExecResult(
                "session-1", "cmd-1", "completed", 0, "ok".getBytes(StandardCharsets.UTF_8), new byte[0], false, false);

        try (var session1 = limitedProvider.open(limitedProfile, workspaceId);
                var session2 = limitedProvider.open(limitedProfile, workspaceId)) {
            var future1 =
                    CompletableFuture.supplyAsync(() -> session1.execute(execution(ExecutionCommand.shell("cmd-1"))));
            assertThat(aio.execEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var result2 = session2.execute(new SandboxExecution(
                    ExecutionCommand.shell("cmd-2"),
                    WorkspacePath.root(workspaceId),
                    Map.of(),
                    new ExecutionLimits(Duration.ofMillis(50), 1024, 1024)));
            assertThat(result2.status()).isEqualTo(SandboxProcessStatus.TIMED_OUT);
            assertThat(result2.failureCode()).isEqualTo("ADMISSION_OVERLOAD");

            aio.releaseExec.countDown();
            var result1 = future1.get(2, TimeUnit.SECONDS);
            assertThat(result1.status()).isEqualTo(SandboxProcessStatus.EXITED);
        }
    }
}
