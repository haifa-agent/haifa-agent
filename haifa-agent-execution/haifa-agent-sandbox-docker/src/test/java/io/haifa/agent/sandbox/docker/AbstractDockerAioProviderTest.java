package io.haifa.agent.sandbox.docker;

import io.haifa.agent.common.id.IdentifierGenerator;
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
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class AbstractDockerAioProviderTest {
    protected static final String IMAGE = "ghcr.io/agent-infra/sandbox@sha256:" + "a".repeat(64);
    protected static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @TempDir
    protected Path workspaceRoot;

    protected InMemoryWorkspaceStore workspaces;
    protected HostWorkspaceLocationStore locations;
    protected WorkspaceId workspaceId;
    protected SandboxProfile profile;
    protected FakeEngine engine;
    protected FakeAio aio;
    protected MutableTime time;
    protected DockerAioSandboxConfiguration configuration;
    protected DockerAioSandboxProvider provider;

    protected abstract DockerAioLifecycleMode lifecycleMode();

    @BeforeEach
    void setUp() {
        workspaces = new InMemoryWorkspaceStore();
        locations = new HostWorkspaceLocationStore();
        workspaceId = new WorkspaceId("workspace-1");
        registerWorkspace(workspaceId, workspaceRoot);

        configuration = defaultConfiguration(lifecycleMode());
        profile = createProfile(configuration);
        engine = new FakeEngine();
        aio = new FakeAio();
        time = new MutableTime(NOW);
        provider = createProvider(configuration, () -> "session-1");
    }

    protected DockerAioSandboxConfiguration defaultConfiguration(DockerAioLifecycleMode mode) {
        return defaultConfiguration(mode, 8, Duration.ofMinutes(15));
    }

    protected DockerAioSandboxConfiguration defaultConfiguration(
            DockerAioLifecycleMode mode, int maxConcurrentSessions, Duration idleTimeout) {
        return defaultConfiguration(mode, maxConcurrentSessions, idleTimeout, workspaceRoot.resolve("docker.sock"));
    }

    protected DockerAioSandboxConfiguration defaultConfiguration(
            DockerAioLifecycleMode mode, int maxConcurrentSessions, Duration idleTimeout, Path socket) {
        return new DockerAioSandboxConfiguration(
                "install-1",
                socket,
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
                maxConcurrentSessions,
                mode,
                idleTimeout);
    }

    protected SandboxProfile createProfile(DockerAioSandboxConfiguration config) {
        return new SandboxProfile(
                new SandboxProfileRef("docker-aio-execution", "1"),
                DockerAioSandboxProvider.PROVIDER_ID,
                config.configurationDigest(),
                Set.of("/bin/bash"),
                Set.of(),
                true);
    }

    protected void registerWorkspace(WorkspaceId id, Path root) {
        locations.register(id, root);
        workspaces.create(Workspace.provision(
                        id, new ProjectId("project-1"), WorkspaceRevision.initial("revision-" + id.value()), NOW)
                .activate(NOW));
    }

    protected DockerAioSandboxProvider createProvider(
            DockerAioSandboxConfiguration config, IdentifierGenerator sessionIdProvider) {
        return new DockerAioSandboxProvider(config, workspaces, locations, sessionIdProvider, time, engine, aio);
    }

    protected SandboxExecution execution(ExecutionCommand command) {
        return execution(workspaceId, command);
    }

    protected SandboxExecution execution(WorkspaceId id, ExecutionCommand command) {
        return new SandboxExecution(
                command, WorkspacePath.root(id), Map.of(), new ExecutionLimits(Duration.ofSeconds(5), 1024, 1024));
    }

    protected static final class MutableTime implements TimeProvider {
        private volatile Instant current;

        public MutableTime(Instant initial) {
            this.current = initial;
        }

        public void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        public void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public Instant now() {
            return current;
        }
    }

    protected static final class FakeEngine implements DockerEngine {
        public final List<String> calls = new ArrayList<>();
        public int networkCreateCount;
        public int networkRemoveCount;
        public int containerCreateCount;
        public int containerStartCount;
        public int containerStopCount;
        public int containerRemoveCount;

        public final Map<String, ContainerInspection> containers = new LinkedHashMap<>();
        public final Map<String, NetworkInspection> networks = new LinkedHashMap<>();

        public String networkName;
        public Map<String, String> networkLabels = Map.of();
        public Map<String, String> networkOptions = Map.of();
        public String containerId = "container-1";
        public ContainerSpec spec;
        public ContainerInspection inspection;
        public int managementPort = 32768;
        public String containerIp = "172.18.0.2";

        public boolean ensureImageDelay;
        public boolean keepContainerAfterRemove;
        public boolean lostCreateResponse;
        public boolean driftLabels;
        public boolean failInspectNetworkDuringTeardown;
        public boolean failInspectContainerDuringTeardown;
        public volatile boolean failVerify;
        public CountDownLatch createEntered;
        public CountDownLatch releaseCreate;

        @Override
        public void ensureImage(String image) {
            calls.add("image");
            if (ensureImageDelay) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public String createNetwork(String name, Map<String, String> labels, Map<String, String> options) {
            calls.add("network-create");
            networkCreateCount++;
            networkName = name;
            networkLabels = Map.copyOf(labels);
            networkOptions = Map.copyOf(options);
            NetworkInspection net = new NetworkInspection(name, "bridge", true, options, labels);
            networks.put(name, net);
            return "net-" + name;
        }

        @Override
        public NetworkInspection inspectNetwork(String name) {
            calls.add("network-inspect");
            if (failInspectNetworkDuringTeardown && calls.contains("container-remove")) {
                throw new RuntimeException("Docker daemon network inspect failed during teardown");
            }
            return networks.get(name);
        }

        @Override
        public void removeNetwork(String name) {
            calls.add("network-remove");
            networkRemoveCount++;
            if (!keepContainerAfterRemove) {
                networks.remove(name);
                if (Objects.equals(networkName, name)) {
                    networkName = null;
                }
            }
        }

        @Override
        public String createContainer(ContainerSpec spec) {
            calls.add("container-create");
            containerCreateCount++;
            this.spec = spec;
            if (createEntered != null) {
                createEntered.countDown();
                try {
                    if (releaseCreate != null) {
                        releaseCreate.await(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            String id = "cid-" + spec.name();
            ContainerInspection insp = inspectionFor(spec, false);
            this.inspection = insp;
            containers.put(id, insp);
            containers.put(spec.name(), insp);
            containers.put("container-1", insp);
            if (lostCreateResponse) {
                throw new DockerEngineException("DOCKER_TIMEOUT", "create response was lost");
            }
            return id;
        }

        @Override
        public void startContainer(String id) {
            calls.add("container-start");
            containerStartCount++;
            ContainerInspection existing = containers.get(id);
            if (existing == null) {
                existing = inspection;
            }
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
                this.inspection = running;
                containers.put(id, running);
                containers.put(existing.name(), running);
                containers.put("container-1", running);
            }
        }

        @Override
        public ContainerInspection inspectContainer(String idOrName) {
            calls.add("container-inspect");
            if (failInspectContainerDuringTeardown && calls.contains("container-remove")) {
                throw new RuntimeException("Docker daemon container inspect failed during teardown");
            }
            ContainerInspection existing = containers.get(idOrName);
            if (existing == null && ("container-1".equals(idOrName) || Objects.equals(idOrName, containerId))) {
                existing = inspection;
            }
            if (existing != null && failVerify) {
                return new ContainerInspection(
                        existing.id(),
                        existing.name(),
                        existing.image(),
                        existing.networkMode(),
                        existing.mounts(),
                        Map.of(),
                        existing.networks(),
                        existing.running(),
                        existing.nanoCpus(),
                        existing.memoryBytes(),
                        existing.pidsLimit(),
                        existing.capDrop(),
                        existing.securityOptions(),
                        existing.privileged(),
                        existing.user(),
                        existing.managementPort(),
                        existing.containerIp());
            }
            return existing;
        }

        @Override
        public void stopContainer(String id, Duration timeout) {
            calls.add("container-stop");
            containerStopCount++;
        }

        @Override
        public void removeContainer(String id) {
            calls.add("container-remove");
            containerRemoveCount++;
            if (!keepContainerAfterRemove) {
                ContainerInspection existing = containers.remove(id);
                if (existing != null) {
                    containers.remove(existing.name());
                }
                containers.remove("container-1");
                this.inspection = null;
            }
        }

        @Override
        public List<String> listContainersByLabel(String key, String value) {
            return containers.values().stream()
                    .filter(c -> value.equals(c.labels().get(key)))
                    .map(ContainerInspection::id)
                    .distinct()
                    .toList();
        }

        @Override
        public List<String> listNetworksByLabel(String key, String value) {
            return networks.values().stream()
                    .filter(n -> value.equals(n.labels().get(key)))
                    .map(NetworkInspection::name)
                    .distinct()
                    .toList();
        }

        private ContainerInspection inspectionFor(ContainerSpec spec, boolean running) {
            Map<String, String> labels = new LinkedHashMap<>(spec.labels());
            if (driftLabels) {
                labels.put(DockerAioSandboxProvider.LABEL_SESSION, "other-session");
            }
            return new ContainerInspection(
                    "cid-" + spec.name(),
                    spec.name(),
                    spec.image(),
                    spec.networkName(),
                    List.of(new MountInspection(spec.hostWorkspaceRoot(), spec.containerWorkspaceRoot(), false)),
                    Map.copyOf(labels),
                    Map.of(spec.networkName(), "net-id"),
                    running,
                    spec.nanoCpus(),
                    spec.memoryBytes(),
                    spec.pidsLimit(),
                    List.of("ALL"),
                    List.of("no-new-privileges"),
                    false,
                    "",
                    managementPort,
                    containerIp);
        }
    }

    protected static final class FakeAio implements AioClient {
        public URI lastUri;
        public int execCount;
        public int killCount;
        public int closeSessionCount;
        public ExecRequest lastRequest;
        public ExecResult result;
        public boolean ready = true;
        public CountDownLatch readyEntered;
        public CountDownLatch releaseReady;
        public CountDownLatch execEntered;
        public CountDownLatch releaseExec;
        public volatile CountDownLatch execStartedLatch;
        public volatile CountDownLatch execBlockLatch;
        public volatile String nextStatus = "completed";
        public volatile Integer nextExitCode = 0;
        public RuntimeException execException;

        @Override
        public boolean awaitReady(URI baseUri, Duration timeout) {
            if (readyEntered != null) {
                readyEntered.countDown();
                try {
                    if (releaseReady != null) {
                        releaseReady.await(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return ready;
        }

        @Override
        public ExecResult exec(URI baseUri, ExecRequest request) {
            lastUri = baseUri;
            execCount++;
            lastRequest = request;
            if (execEntered != null) {
                execEntered.countDown();
            }
            if (execStartedLatch != null) {
                execStartedLatch.countDown();
            }
            if (releaseExec != null) {
                try {
                    releaseExec.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (execBlockLatch != null) {
                try {
                    execBlockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AioClient.AioProtocolException("AIO_INTERRUPTED", "AIO request interrupted", e);
                }
            }
            if (execException != null) {
                throw execException;
            }
            if (result != null) {
                return result;
            }
            return new ExecResult(
                    "session-" + execCount,
                    "command-" + execCount,
                    nextStatus,
                    nextExitCode,
                    "ok\n".getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    false,
                    false);
        }

        @Override
        public void kill(URI baseUri, String sessionId, Duration timeout) {
            killCount++;
        }

        @Override
        public void closeSession(URI baseUri, String sessionId, Duration timeout) {
            closeSessionCount++;
        }
    }
}
