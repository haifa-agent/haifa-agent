package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionLimits;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerAioWarmSandboxProviderTest {
    private static final String IMAGE = "ghcr.io/agent-infra/sandbox@sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path workspaceRoot2;

    private DockerAioSandboxConfiguration configuration;
    private FakeEngine engine;
    private FakeAio aio;
    private DockerAioSandboxProvider provider;
    private SandboxProfile profile;
    private WorkspaceId workspaceId;
    private WorkspaceId workspaceId2;
    private MutableTime time;

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
                8080,
                8,
                DockerAioLifecycleMode.WARM_SESSION,
                Duration.ofMinutes(15));
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        workspaceId = new WorkspaceId("workspace-1");
        workspaceId2 = new WorkspaceId("workspace-2");
        locations.register(workspaceId, workspaceRoot);
        locations.register(workspaceId2, workspaceRoot2);
        workspaces.create(Workspace.provision(
                        workspaceId, new ProjectId("project-1"), WorkspaceRevision.initial("revision-1"), NOW)
                .activate(NOW));
        workspaces.create(Workspace.provision(
                        workspaceId2, new ProjectId("project-1"), WorkspaceRevision.initial("revision-2"), NOW)
                .activate(NOW));
        engine = new FakeEngine();
        aio = new FakeAio();
        time = new MutableTime(NOW);
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
            session2.execute(executionFor(workspaceId2, ExecutionCommand.shell("ws2-cmd")));
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
            session2.execute(executionFor(workspaceId2, ExecutionCommand.shell("echo 2")));
        }
        assertThat(provider.warmContainerCount()).isEqualTo(2);

        provider.close();
        assertThat(provider.warmContainerCount()).isZero();
        assertThat(engine.containerStopCount).isEqualTo(2);
        assertThat(engine.containerRemoveCount).isEqualTo(2);
        assertThat(engine.networkRemoveCount).isEqualTo(2);
    }

    private SandboxExecution execution(ExecutionCommand command) {
        return executionFor(workspaceId, command);
    }

    private SandboxExecution executionFor(WorkspaceId wsId, ExecutionCommand command) {
        return new SandboxExecution(
                command, WorkspacePath.root(wsId), Map.of(), new ExecutionLimits(Duration.ofSeconds(5), 1024, 1024));
    }

    private static final class MutableTime implements TimeProvider {
        private Instant current;

        MutableTime(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public Instant now() {
            return current;
        }
    }

    private static final class FakeEngine implements DockerEngine {
        private final List<String> calls = new ArrayList<>();
        private int networkCreateCount;
        private int networkRemoveCount;
        private int containerCreateCount;
        private int containerStartCount;
        private int containerStopCount;
        private int containerRemoveCount;
        private final Map<String, ContainerInspection> containers = new LinkedHashMap<>();
        private final Map<String, NetworkInspection> networks = new LinkedHashMap<>();

        @Override
        public void ensureImage(String image) {
            calls.add("image");
        }

        @Override
        public String createNetwork(String name, Map<String, String> labels, Map<String, String> options) {
            calls.add("network-create:" + name);
            networkCreateCount++;
            networks.put(name, new NetworkInspection(name, "bridge", true, options, labels));
            return "net-" + name;
        }

        @Override
        public NetworkInspection inspectNetwork(String name) {
            return networks.get(name);
        }

        @Override
        public void removeNetwork(String name) {
            calls.add("network-remove:" + name);
            networkRemoveCount++;
            networks.remove(name);
        }

        @Override
        public String createContainer(ContainerSpec spec) {
            calls.add("container-create:" + spec.name());
            containerCreateCount++;
            String id = "cid-" + spec.name();
            ContainerInspection inspection = new ContainerInspection(
                    id,
                    spec.name(),
                    spec.image(),
                    spec.networkName(),
                    List.of(new MountInspection(spec.hostWorkspaceRoot(), spec.containerWorkspaceRoot(), false)),
                    Map.copyOf(spec.labels()),
                    Map.of(spec.networkName(), "net-id"),
                    false,
                    spec.nanoCpus(),
                    spec.memoryBytes(),
                    spec.pidsLimit(),
                    List.of("ALL"),
                    List.of("no-new-privileges"),
                    false,
                    "",
                    32768,
                    "172.18.0.2");
            containers.put(id, inspection);
            containers.put(spec.name(), inspection);
            return id;
        }

        @Override
        public void startContainer(String id) {
            calls.add("container-start:" + id);
            containerStartCount++;
            ContainerInspection existing = containers.get(id);
            if (existing != null) {
                ContainerInspection running = new ContainerInspection(
                        existing.id(),
                        existing.name(),
                        existing.image(),
                        existing.networkMode(),
                        existing.mounts(),
                        existing.labels(),
                        existing.networks(),
                        true,
                        existing.nanoCpus(),
                        existing.memoryBytes(),
                        existing.pidsLimit(),
                        existing.capDrop(),
                        existing.securityOptions(),
                        existing.privileged(),
                        existing.user(),
                        existing.managementPort(),
                        existing.containerIp());
                containers.put(id, running);
                containers.put(existing.name(), running);
            }
        }

        @Override
        public ContainerInspection inspectContainer(String idOrName) {
            return containers.get(idOrName);
        }

        @Override
        public void stopContainer(String id, Duration timeout) {
            calls.add("container-stop:" + id);
            containerStopCount++;
        }

        @Override
        public void removeContainer(String id) {
            calls.add("container-remove:" + id);
            containerRemoveCount++;
            ContainerInspection existing = containers.remove(id);
            if (existing != null) {
                containers.remove(existing.name());
            }
        }
    }

    private static final class FakeAio implements AioClient {
        private int execCount;

        @Override
        public boolean awaitReady(URI baseUri, Duration timeout) {
            return true;
        }

        @Override
        public ExecResult exec(URI baseUri, ExecRequest request) {
            execCount++;
            return new ExecResult(
                    "session-" + execCount,
                    "command-" + execCount,
                    "completed",
                    0,
                    "ok\n".getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    false,
                    false);
        }

        @Override
        public void kill(URI baseUri, String sessionId, Duration timeout) {}

        @Override
        public void closeSession(URI baseUri, String sessionId, Duration timeout) {}
    }
}
