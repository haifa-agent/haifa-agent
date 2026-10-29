package io.haifa.agent.sandbox.docker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Narrow Docker Engine boundary used by the provider.
 *
 * <p>The production implementation uses a local Unix socket or Windows named pipe. Tests replace
 * this interface with an in-memory engine; container failures never enable host execution.
 */
interface DockerEngine extends AutoCloseable {
    void ensureImage(String image);

    String createNetwork(String name, Map<String, String> labels, Map<String, String> options);

    NetworkInspection inspectNetwork(String name);

    void removeNetwork(String name);

    String createContainer(ContainerSpec spec);

    void startContainer(String id);

    ContainerInspection inspectContainer(String idOrName);

    void stopContainer(String id, Duration timeout);

    void removeContainer(String id);

    default List<String> listContainersByLabel(String key, String value) {
        return List.of();
    }

    default List<String> listNetworksByLabel(String key, String value) {
        return List.of();
    }

    @Override
    default void close() {}

    final class DockerEngineException extends RuntimeException {
        private final String code;

        DockerEngineException(String code, String safeMessage) {
            super(safeMessage);
            this.code = code;
        }

        DockerEngineException(String code, String safeMessage, Throwable cause) {
            super(safeMessage, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record NetworkInspection(
            String name, String driver, boolean internal, Map<String, String> options, Map<String, String> labels) {}

    record ContainerSpec(
            String name,
            String image,
            Map<String, String> labels,
            String networkName,
            String hostWorkspaceRoot,
            String containerWorkspaceRoot,
            Map<String, String> environment,
            long nanoCpus,
            long memoryBytes,
            long pidsLimit,
            int aioPort,
            OptionalInt uid,
            OptionalInt gid) {}

    record MountInspection(String source, String destination, boolean readOnly) {}

    record ContainerInspection(
            String id,
            String name,
            String image,
            String networkMode,
            List<MountInspection> mounts,
            Map<String, String> labels,
            Map<String, String> networks,
            boolean running,
            long nanoCpus,
            long memoryBytes,
            long pidsLimit,
            List<String> capDrop,
            List<String> securityOptions,
            boolean privileged,
            String user,
            int managementPort,
            String containerIp) {}
}
