package io.haifa.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Production Docker Engine transport over one explicit Unix socket. */
final class DockerJavaEngine implements DockerEngine {
    private final DockerClient client;

    DockerJavaEngine(DockerAioSandboxConfiguration configuration) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix://" + configuration.dockerSocket())
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(configuration.engineTimeout())
                .responseTimeout(configuration.engineTimeout())
                .build();
        this.client = DockerClientImpl.getInstance(config, httpClient);
    }

    @Override
    public void ensureImage(String image) {
        try {
            var inspected = client.inspectImageCmd(image).exec();
            if (inspected == null
                    || inspected.getId() == null
                    || inspected.getId().isBlank()) {
                throw new DockerEngineException("DOCKER_IMAGE_UNAVAILABLE", "locked image is unavailable");
            }
        } catch (NotFoundException exception) {
            throw new DockerEngineException("DOCKER_IMAGE_UNAVAILABLE", "locked image is unavailable", exception);
        } catch (RuntimeException exception) {
            throw failure("DOCKER_IMAGE_UNAVAILABLE", "locked image could not be inspected", exception);
        }
    }

    @Override
    public String createNetwork(String name, Map<String, String> labels, Map<String, String> options) {
        try {
            var response = client.createNetworkCmd()
                    .withName(name)
                    .withDriver(DockerAioSandboxConfiguration.NETWORK_DRIVER)
                    .withInternal(true)
                    .withOptions(options)
                    .withLabels(labels)
                    .exec();
            if (response == null || response.getId() == null || response.getId().isBlank()) {
                throw new DockerEngineException("DOCKER_NETWORK_CREATE_FAILED", "sandbox network was not created");
            }
            return response.getId();
        } catch (RuntimeException exception) {
            throw failure("DOCKER_NETWORK_CREATE_FAILED", "sandbox network could not be created", exception);
        }
    }

    @Override
    public NetworkInspection inspectNetwork(String name) {
        try {
            Network network = client.inspectNetworkCmd().withNetworkId(name).exec();
            if (network == null) return null;
            return new NetworkInspection(
                    network.getName(),
                    network.getDriver(),
                    Boolean.TRUE.equals(network.getInternal()),
                    copy(network.getOptions()),
                    copy(network.getLabels()));
        } catch (NotFoundException exception) {
            return null;
        } catch (RuntimeException exception) {
            throw failure("DOCKER_NETWORK_INSPECT_FAILED", "sandbox network could not be inspected", exception);
        }
    }

    @Override
    public void removeNetwork(String name) {
        try {
            client.removeNetworkCmd(name).exec();
        } catch (NotFoundException exception) {
            // Exact identity is already absent.
        } catch (RuntimeException exception) {
            throw failure("DOCKER_NETWORK_DELETE_FAILED", "sandbox network could not be removed", exception);
        }
    }

    @Override
    public String createContainer(ContainerSpec spec) {
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode(spec.networkName())
                    .withBinds(new Bind(
                            spec.hostWorkspaceRoot(), new Volume(spec.containerWorkspaceRoot()), AccessMode.rw))
                    .withCapDrop(Capability.ALL)
                    .withSecurityOpts(List.of("no-new-privileges"))
                    .withPrivileged(false)
                    .withNanoCPUs(spec.nanoCpus())
                    .withMemory(spec.memoryBytes())
                    .withPidsLimit(spec.pidsLimit())
                    .withPortBindings(PortBinding.parse("127.0.0.1::" + spec.aioPort()));
            var command = client.createContainerCmd(spec.image())
                    .withName(spec.name())
                    .withLabels(spec.labels())
                    .withHostConfig(hostConfig)
                    .withWorkingDir(spec.containerWorkspaceRoot())
                    .withExposedPorts(ExposedPort.tcp(spec.aioPort()))
                    .withEnv(toEnvironment(spec.environment()));
            if (spec.uid().isPresent()) {
                command.withUser(spec.uid().getAsInt() + ":" + spec.gid().getAsInt());
            }
            var response = command.exec();
            if (response == null || response.getId() == null || response.getId().isBlank()) {
                throw new DockerEngineException("DOCKER_CONTAINER_CREATE_FAILED", "sandbox container was not created");
            }
            return response.getId();
        } catch (RuntimeException exception) {
            throw failure("DOCKER_CONTAINER_CREATE_FAILED", "sandbox container could not be created", exception);
        }
    }

    @Override
    public void startContainer(String id) {
        try {
            client.startContainerCmd(id).exec();
        } catch (RuntimeException exception) {
            throw failure("DOCKER_CONTAINER_START_FAILED", "sandbox container could not be started", exception);
        }
    }

    @Override
    public ContainerInspection inspectContainer(String idOrName) {
        try {
            var inspected = client.inspectContainerCmd(idOrName).exec();
            if (inspected == null) return null;
            var host = inspected.getHostConfig();
            var networkSettings = inspected.getNetworkSettings();
            Map<String, String> networks = new LinkedHashMap<>();
            if (networkSettings != null && networkSettings.getNetworks() != null) {
                networkSettings.getNetworks().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry ->
                                networks.put(entry.getKey(), entry.getValue().getNetworkID()));
            }
            List<MountInspection> mounts = new ArrayList<>();
            if (inspected.getMounts() != null) {
                for (var mount : inspected.getMounts()) {
                    mounts.add(new MountInspection(
                            mount.getSource(), mount.getDestination().getPath(), Boolean.FALSE.equals(mount.getRW())));
                }
            }
            return new ContainerInspection(
                    inspected.getId(),
                    stripSlash(inspected.getName()),
                    inspected.getConfig() == null ? null : inspected.getConfig().getImage(),
                    host == null ? null : host.getNetworkMode(),
                    List.copyOf(mounts),
                    inspected.getConfig() == null
                            ? Map.of()
                            : copy(inspected.getConfig().getLabels()),
                    networks,
                    inspected.getState() != null
                            && Boolean.TRUE.equals(inspected.getState().getRunning()),
                    host == null || host.getNanoCPUs() == null ? 0 : host.getNanoCPUs(),
                    host == null || host.getMemory() == null ? 0 : host.getMemory(),
                    host == null || host.getPidsLimit() == null ? 0 : host.getPidsLimit(),
                    capabilityNames(host == null ? null : host.getCapDrop()),
                    host == null || host.getSecurityOpts() == null ? List.of() : List.copyOf(host.getSecurityOpts()),
                    host != null && Boolean.TRUE.equals(host.getPrivileged()),
                    inspected.getConfig() == null ? null : inspected.getConfig().getUser(),
                    managementPort(networkSettings == null ? null : networkSettings.getPorts()));
        } catch (NotFoundException exception) {
            return null;
        } catch (RuntimeException exception) {
            throw failure("DOCKER_CONTAINER_INSPECT_FAILED", "sandbox container could not be inspected", exception);
        }
    }

    @Override
    public void stopContainer(String id, Duration timeout) {
        try {
            client.stopContainerCmd(id)
                    .withTimeout((int) Math.max(1, Math.min(60, timeout.toSeconds() + 1)))
                    .exec();
        } catch (NotFoundException exception) {
            // Exact identity is already absent.
        } catch (RuntimeException exception) {
            throw failure("DOCKER_CONTAINER_STOP_FAILED", "sandbox container could not be stopped", exception);
        }
    }

    @Override
    public void removeContainer(String id) {
        try {
            client.removeContainerCmd(id)
                    .withForce(true)
                    .withRemoveVolumes(false)
                    .exec();
        } catch (NotFoundException exception) {
            // Exact identity is already absent.
        } catch (RuntimeException exception) {
            throw failure("DOCKER_CONTAINER_DELETE_FAILED", "sandbox container could not be removed", exception);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    private static int managementPort(Ports ports) {
        if (ports == null || ports.getBindings() == null) return 0;
        var bindings = ports.getBindings().get(ExposedPort.tcp(DockerAioSandboxConfiguration.DEFAULT_AIO_PORT));
        if (bindings == null) {
            var any = ports.getBindings().values().stream()
                    .flatMap(Arrays::stream)
                    .findFirst();
            if (any.isEmpty() || any.get().getHostPortSpec() == null) return 0;
            try {
                return Integer.parseInt(any.get().getHostPortSpec());
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        for (var binding : bindings) {
            if (binding.getHostPortSpec() != null) {
                try {
                    return Integer.parseInt(binding.getHostPortSpec());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static List<String> capabilityNames(Capability[] capabilities) {
        if (capabilities == null) return List.of();
        return Arrays.stream(capabilities).map(value -> value.name()).toList();
    }

    private static List<String> toEnvironment(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        environment.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(entry.getKey() + "=" + entry.getValue()));
        return values;
    }

    private static Map<String, String> copy(Map<String, String> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static String stripSlash(String name) {
        if (name == null) return null;
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static DockerEngineException failure(String code, String message, RuntimeException cause) {
        if (cause instanceof DockerEngineException engineException) return engineException;
        return new DockerEngineException(code, message, cause);
    }

    static String normalizedUser(String user) {
        return user == null ? null : user.trim().toLowerCase(Locale.ROOT);
    }
}
