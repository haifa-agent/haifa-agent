package io.haifa.agent.sandbox.docker;

import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trusted deployment configuration of the {@code docker-aio} provider.
 *
 * <p>Every value is supplied by the product assembly, never by a model or a Tool argument. The
 * configuration intentionally stays in this provider module: image, engine endpoint, mounts,
 * UID/GID, network, resources and deadlines are not part of the generic {@code SandboxProfile}.
 */
public record DockerAioSandboxConfiguration(
        String installationId,
        Path dockerSocket,
        String image,
        String containerWorkspaceRoot,
        Map<String, String> runtimes,
        OptionalInt uid,
        OptionalInt gid,
        long nanoCpus,
        long memoryBytes,
        long pidsLimit,
        Duration engineTimeout,
        Duration stopTimeout,
        int aioPort) {

    public static final String PROVIDER_ID = "docker-aio";
    public static final int DEFAULT_AIO_PORT = 8080;
    public static final String NETWORK_DRIVER = "bridge";
    public static final String GATEWAY_MODE_IPV4 = "com.docker.network.bridge.gateway_mode_ipv4";
    public static final String GATEWAY_MODE_IPV6 = "com.docker.network.bridge.gateway_mode_ipv6";
    public static final String ENABLE_ICC = "com.docker.network.bridge.enable_icc";

    private static final Pattern INSTALLATION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern IMAGE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}$");
    private static final Pattern POSIX_ABSOLUTE = Pattern.compile("^/[A-Za-z0-9._/-]+$");
    private static final Set<String> SHELL_RUNTIMES = Set.of("bash", "python");
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_ENGINE_TIMEOUT = Duration.ofSeconds(60);
    private static final long MAX_NANO_CPUS = 64L * 1_000_000_000L;

    public DockerAioSandboxConfiguration {
        installationId = require(installationId, "installationId").trim();
        if (!INSTALLATION.matcher(installationId).matches()) {
            throw new IllegalArgumentException("installationId is invalid");
        }
        dockerSocket = Objects.requireNonNull(dockerSocket, "dockerSocket must not be null")
                .toAbsolutePath()
                .normalize();
        image = require(image, "image").trim();
        if (!IMAGE.matcher(image).matches()) {
            throw new IllegalArgumentException("image must be a locked repository@sha256 digest");
        }
        containerWorkspaceRoot =
                require(containerWorkspaceRoot, "containerWorkspaceRoot").trim();
        requireContainerPath(containerWorkspaceRoot, "containerWorkspaceRoot");
        if (containerWorkspaceRoot.equals("/")) {
            throw new IllegalArgumentException("containerWorkspaceRoot must not be the filesystem root");
        }
        runtimes = copyRuntimes(runtimes);
        if (uid.isPresent() != gid.isPresent()) {
            throw new IllegalArgumentException("uid and gid must be declared together");
        }
        if (uid.isPresent() && (uid.getAsInt() < 0 || gid.getAsInt() < 0)) {
            throw new IllegalArgumentException("uid and gid must not be negative");
        }
        if (nanoCpus < 1 || nanoCpus > MAX_NANO_CPUS) {
            throw new IllegalArgumentException("nanoCpus is out of range");
        }
        if (memoryBytes < 16L * 1024 * 1024 || memoryBytes > 512L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("memoryBytes is out of range");
        }
        if (pidsLimit < 16 || pidsLimit > 1_000_000) {
            throw new IllegalArgumentException("pidsLimit is out of range");
        }
        engineTimeout = requireTimeout(engineTimeout, "engineTimeout", MAX_ENGINE_TIMEOUT);
        stopTimeout = requireTimeout(stopTimeout, "stopTimeout", MAX_ENGINE_TIMEOUT);
        if (aioPort < 1 || aioPort > 65_535) {
            throw new IllegalArgumentException("aioPort is out of range");
        }
    }

    public SandboxConfigurationDigest configurationDigest() {
        List<String> fields = new ArrayList<>();
        fields.add("docker-aio/v1");
        fields.add(installationId);
        fields.add(dockerSocket.toString());
        fields.add(image);
        fields.add(containerWorkspaceRoot);
        runtimes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.add("runtime:" + entry.getKey() + "=" + entry.getValue()));
        fields.add("uid:" + (uid.isPresent() ? uid.getAsInt() : "image-default"));
        fields.add("gid:" + (gid.isPresent() ? gid.getAsInt() : "image-default"));
        fields.add("nanoCpus:" + nanoCpus);
        fields.add("memory:" + memoryBytes);
        fields.add("pids:" + pidsLimit);
        fields.add("engineTimeout:" + engineTimeout.toMillis());
        fields.add("stopTimeout:" + stopTimeout.toMillis());
        fields.add("aioPort:" + aioPort);
        fields.add("network:" + NETWORK_DRIVER + ":internal:icc=false:gateway=isolated");
        return SandboxConfigurationDigest.sha256Fields(fields);
    }

    public String containerPath(String workspaceRelativePath) {
        String relative = Objects.requireNonNull(workspaceRelativePath, "workspaceRelativePath must not be null")
                .trim()
                .replace('\\', '/');
        if (relative.isEmpty() || relative.equals(".")) {
            return containerWorkspaceRoot;
        }
        if (relative.startsWith("/") || relative.contains("..") || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("workspace relative path is invalid");
        }
        return containerWorkspaceRoot + "/" + relative;
    }

    private static Map<String, String> copyRuntimes(Map<String, String> value) {
        Objects.requireNonNull(value, "runtimes must not be null");
        Map<String, String> copied = new java.util.TreeMap<>();
        value.forEach((language, executable) -> {
            String normalized = require(language, "runtime language").trim().toLowerCase(Locale.ROOT);
            if (!SHELL_RUNTIMES.contains(normalized)) {
                throw new IllegalArgumentException("runtime language is not supported: " + normalized);
            }
            requireContainerPath(executable, "runtime executable");
            copied.put(normalized, executable.trim());
        });
        return Map.copyOf(copied);
    }

    private static void requireContainerPath(String value, String field) {
        String path = require(value, field).trim().replace('\\', '/');
        if (!POSIX_ABSOLUTE.matcher(path).matches()
                || path.contains("//")
                || path.contains("/../")
                || path.endsWith("/.")
                || path.endsWith("/..")) {
            throw new IllegalArgumentException(field + " must be a plain absolute POSIX path");
        }
    }

    private static Duration requireTimeout(Duration value, String field, Duration maximum) {
        Duration timeout = Objects.requireNonNull(value, field + " must not be null");
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return timeout;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 512 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
