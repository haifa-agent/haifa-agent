package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerAioSandboxProviderTest {
    private static final String IMAGE = "ghcr.io/agent-infra/sandbox@sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @TempDir
    Path workspaceRoot;

    private DockerAioSandboxConfiguration configuration;
    private FakeEngine engine;
    private FakeAio aio;
    private DockerAioSandboxProvider provider;
    private SandboxProfile profile;
    private WorkspaceId workspaceId;

    @BeforeEach
    void setUp() {
        configuration = new DockerAioSandboxConfiguration(
                "install-1",
                workspaceRoot.resolve("docker.sock"),
                IMAGE,
                "/mnt/user-data/workspace",
                Map.of("bash", "/bin/bash"),
                OptionalInt.empty(),
                OptionalInt.empty(),
                1_000_000_000L,
                256L * 1024 * 1024,
                128,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                8080);
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        workspaceId = new WorkspaceId("workspace-1");
        locations.register(workspaceId, workspaceRoot);
        workspaces.create(Workspace.provision(
                        workspaceId, new ProjectId("project-1"), WorkspaceRevision.initial("revision-1"), NOW)
                .activate(NOW));
        engine = new FakeEngine();
        aio = new FakeAio();
        TimeProvider time = () -> NOW;
        provider = new DockerAioSandboxProvider(
                configuration, workspaces, locations, () -> "session-1", time, engine, aio);
        profile = new SandboxProfile(
                new SandboxProfileRef("docker-aio-execution", "1"),
                DockerAioSandboxProvider.PROVIDER_ID,
                configuration.configurationDigest(),
                java.util.Set.of("/bin/bash"),
                java.util.Set.of(),
                true);
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

    private SandboxExecution execution(ExecutionCommand command) {
        return new SandboxExecution(
                command,
                WorkspacePath.root(workspaceId),
                Map.of(),
                new ExecutionLimits(Duration.ofSeconds(5), 1024, 1024));
    }

    private static final class FakeEngine implements DockerEngine {
        private final List<String> calls = new ArrayList<>();
        private String networkName;
        private Map<String, String> networkLabels = Map.of();
        private Map<String, String> networkOptions = Map.of();
        private String containerId = "container-1";
        private ContainerSpec spec;
        private ContainerInspection inspection;
        private boolean keepContainerAfterRemove;
        private boolean lostCreateResponse;
        private boolean driftLabels;
        private CountDownLatch createEntered;
        private CountDownLatch releaseCreate;

        @Override
        public void ensureImage(String image) {
            calls.add("image");
        }

        @Override
        public String createNetwork(String name, Map<String, String> labels, Map<String, String> options) {
            calls.add("network-create");
            networkName = name;
            networkLabels = Map.copyOf(labels);
            networkOptions = Map.copyOf(options);
            return "network-id";
        }

        @Override
        public NetworkInspection inspectNetwork(String name) {
            calls.add("network-inspect");
            if (networkName == null || !networkName.equals(name)) return null;
            return new NetworkInspection(name, "bridge", true, networkOptions, networkLabels);
        }

        @Override
        public void removeNetwork(String name) {
            calls.add("network-remove");
            if (!keepContainerAfterRemove) networkName = null;
        }

        @Override
        public String createContainer(ContainerSpec spec) {
            calls.add("container-create");
            this.spec = spec;
            if (createEntered != null) {
                createEntered.countDown();
                try {
                    releaseCreate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            inspection = inspectionFor(spec, false);
            if (lostCreateResponse) {
                throw new DockerEngineException("DOCKER_TIMEOUT", "create response was lost");
            }
            return containerId;
        }

        @Override
        public void startContainer(String id) {
            calls.add("container-start");
            if (inspection != null) inspection = inspectionFor(spec, true);
        }

        @Override
        public ContainerInspection inspectContainer(String idOrName) {
            calls.add("container-inspect");
            if (inspection == null) return null;
            return inspection;
        }

        @Override
        public void stopContainer(String id, Duration timeout) {
            calls.add("container-stop");
        }

        @Override
        public void removeContainer(String id) {
            calls.add("container-remove");
            if (!keepContainerAfterRemove && inspection != null) inspection = null;
        }

        private ContainerInspection inspectionFor(ContainerSpec spec, boolean running) {
            Map<String, String> labels = new LinkedHashMap<>(spec.labels());
            if (driftLabels) labels.put(DockerAioSandboxProvider.LABEL_SESSION, "other-session");
            return new ContainerInspection(
                    "container-1",
                    spec.name(),
                    spec.image(),
                    spec.networkName(),
                    List.of(new MountInspection(spec.hostWorkspaceRoot(), spec.containerWorkspaceRoot(), false)),
                    Map.copyOf(labels),
                    Map.of(spec.networkName(), "network-id"),
                    running,
                    spec.nanoCpus(),
                    spec.memoryBytes(),
                    spec.pidsLimit(),
                    List.of("ALL"),
                    List.of("no-new-privileges"),
                    false,
                    "",
                    32768);
        }
    }

    private static final class FakeAio implements AioClient {
        private int execCount;
        private ExecRequest lastRequest;
        private ExecResult result;
        private boolean ready = true;
        private CountDownLatch readyEntered;
        private CountDownLatch releaseReady;

        @Override
        public boolean awaitReady(URI baseUri, Duration timeout) {
            if (readyEntered != null) {
                readyEntered.countDown();
                try {
                    releaseReady.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return ready;
        }

        @Override
        public ExecResult exec(URI baseUri, ExecRequest request) {
            execCount++;
            lastRequest = request;
            return result;
        }

        @Override
        public void kill(URI baseUri, String sessionId, Duration timeout) {
            // No-op fake.
        }

        @Override
        public void closeSession(URI baseUri, String sessionId, Duration timeout) {
            // No-op fake.
        }
    }
}
