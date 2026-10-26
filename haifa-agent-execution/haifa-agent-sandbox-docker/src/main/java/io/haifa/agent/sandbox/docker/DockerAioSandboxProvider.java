package io.haifa.agent.sandbox.docker;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.BoundedOutputBuffer;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxManagedProcess;
import io.haifa.agent.sandbox.api.SandboxPreflight;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot Linux Docker + AIO provider.
 *
 * <p>{@link #open} only allocates a local session identity. The container, network, readiness
 * probe, command and teardown are all performed inside {@link SandboxSession#execute} so the
 * request deadline is available and the Broker can cancel an already registered session.
 * Teardown is verified before any successful result is returned; {@link SandboxSession#close()}
 * only retries an idempotent fallback.
 */
public final class DockerAioSandboxProvider implements SandboxProvider, AutoCloseable {
    public static final String PROVIDER_ID = DockerAioSandboxConfiguration.PROVIDER_ID;
    static final String LABEL_INSTALLATION = "io.haifa.agent.sandbox.installation";
    static final String LABEL_SESSION = "io.haifa.agent.sandbox.session";
    static final String LABEL_ROLE = "io.haifa.agent.sandbox.role";
    static final String LABEL_MANAGED = "io.haifa.agent.sandbox.managed";
    private static final String ROLE_CONTAINER = "container";
    private static final String ROLE_NETWORK = "network";
    private static final Duration MINIMUM_CLEANUP_RESERVE = Duration.ofSeconds(1);
    private static final String GATEWAY_ISOLATED = "isolated";

    private final DockerAioSandboxConfiguration configuration;
    private final WorkspaceStore workspaces;
    private final HostWorkspaceLocationStore locations;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private volatile DockerEngine engine;
    private volatile AioClient aio;

    public DockerAioSandboxProvider(
            DockerAioSandboxConfiguration configuration,
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this(configuration, workspaces, locations, identifiers, time, null, null);
    }

    DockerAioSandboxProvider(
            DockerAioSandboxConfiguration configuration,
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            DockerEngine engine,
            AioClient aio) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.engine = engine;
        this.aio = aio;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public SandboxCapabilities capabilities() {
        return new SandboxCapabilities(true);
    }

    @Override
    public SandboxConfigurationDigest configurationDigest() {
        return configuration.configurationDigest();
    }

    @Override
    public boolean supportsManagedProcess() {
        // MCP stdio and long-lived sessions stay on the host in the first phase.
        return false;
    }

    @Override
    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    @Override
    public SandboxPreflight preflight(SandboxProfile profile) {
        return SandboxProvider.super.preflight(profile);
    }

    @Override
    public SandboxSession open(SandboxProfile profile, io.haifa.agent.project.workspace.WorkspaceId workspaceId) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (!PROVIDER_ID.equals(profile.providerId())
                || !configurationDigest().equals(profile.providerConfigurationDigest())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox provider configuration does not match");
        }
        var workspace =
                workspaces.find(workspaceId).orElseThrow(() -> failure("WORKSPACE_NOT_FOUND", "workspace not found"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) {
            throw failure("WORKSPACE_INACTIVE", "workspace is inactive");
        }
        try {
            return new Session(
                    new SandboxSessionId(identifiers.nextValue()),
                    profile,
                    workspaceId,
                    locations.resolveForTrustedProvider(workspaceId));
        } catch (RuntimeException exception) {
            throw failure("WORKSPACE_UNAVAILABLE", "workspace root identity is unavailable");
        }
    }

    private DockerEngine engine() {
        DockerEngine value = engine;
        if (value == null) {
            synchronized (this) {
                value = engine;
                if (value == null) {
                    value = new DockerJavaEngine(configuration);
                    engine = value;
                }
            }
        }
        return value;
    }

    private AioClient aio() {
        AioClient value = aio;
        if (value == null) {
            synchronized (this) {
                value = aio;
                if (value == null) {
                    value = new HttpAioClient(configuration.engineTimeout());
                    aio = value;
                }
            }
        }
        return value;
    }

    private final class Session implements SandboxSession {
        private final SandboxSessionId id;
        private final SandboxProfile profile;
        private final io.haifa.agent.project.workspace.WorkspaceId workspaceId;
        private final java.nio.file.Path hostWorkspaceRoot;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean executed = new AtomicBoolean();
        private final Object teardownLock = new Object();
        private volatile boolean creating;
        private volatile String networkName;
        private volatile String containerName;
        private volatile String containerId;
        private volatile Teardown teardown;
        private volatile String aioSessionId;
        private volatile URI managementUri;
        private volatile boolean dispatched;

        private Session(
                SandboxSessionId id,
                SandboxProfile profile,
                io.haifa.agent.project.workspace.WorkspaceId workspaceId,
                java.nio.file.Path hostWorkspaceRoot) {
            this.id = id;
            this.profile = profile;
            this.workspaceId = workspaceId;
            this.hostWorkspaceRoot = hostWorkspaceRoot;
        }

        @Override
        public SandboxSessionId id() {
            return id;
        }

        @Override
        public SandboxProcessResult execute(SandboxExecution execution) {
            return execute(execution, ExecutionOutputObserver.noop());
        }

        @Override
        public SandboxProcessResult execute(SandboxExecution execution, ExecutionOutputObserver observer) {
            Objects.requireNonNull(execution, "execution must not be null");
            Objects.requireNonNull(observer, "observer must not be null");
            if (closed.get()) throw failure("SESSION_CLOSED", "sandbox session is closed");
            if (!executed.compareAndSet(false, true)) {
                throw failure("SESSION_EXHAUSTED", "sandbox session already executed its single command");
            }
            Instant started = time.now();
            if (cancelled.get()) {
                return result(
                        SandboxProcessStatus.CANCELLED,
                        null,
                        new byte[0],
                        new byte[0],
                        started,
                        started,
                        false,
                        false,
                        true,
                        "CANCELLED");
            }
            validateExecution(execution);
            long deadlineNanos =
                    System.nanoTime() + execution.limits().timeout().toNanos();
            Duration reserve = reserve(execution.limits().timeout());
            long businessDeadline = deadlineNanos - reserve.toNanos();
            try {
                engine().ensureImage(configuration.image());
                createNetwork();
                if (cancelled.get()) {
                    Teardown cancelledTeardown = teardown();
                    return result(
                            SandboxProcessStatus.CANCELLED,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            cancelledTeardown.confirmed(),
                            "CANCELLED");
                }
                createContainer();
                if (cancelled.get()) {
                    Teardown cancelledTeardown = teardown();
                    return result(
                            SandboxProcessStatus.CANCELLED,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            cancelledTeardown.confirmed(),
                            "CANCELLED");
                }
                engine().startContainer(containerId);
                DockerEngine.ContainerInspection inspected = engine().inspectContainer(containerId);
                verifyContainer(inspected);
                managementUri = URI.create("http://127.0.0.1:" + inspected.managementPort());
                if (!aio().awaitReady(managementUri, remaining(businessDeadline))) {
                    Teardown timedOut = teardown();
                    return result(
                            SandboxProcessStatus.TIMED_OUT,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            timedOut.confirmed(),
                            "TIMEOUT");
                }
                if (cancelled.get()) {
                    Teardown cancelledTeardown = teardown();
                    return result(
                            SandboxProcessStatus.CANCELLED,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            cancelledTeardown.confirmed(),
                            "CANCELLED");
                }
                Duration commandBudget = remaining(businessDeadline);
                AioClient.ExecRequest request = new AioClient.ExecRequest(
                        renderCommand(execution),
                        containerWorkingDirectory(execution),
                        environment(execution),
                        commandBudget,
                        commandBudget,
                        Math.max(
                                execution.limits().maxStdoutBytes(),
                                execution.limits().maxStderrBytes()));
                dispatched = true;
                observer.onStarted();
                AioClient.ExecResult exec = aio().exec(managementUri, request);
                aioSessionId = exec.sessionId();
                Teardown completedTeardown = teardown();
                return resultFromExec(exec, execution, started, completedTeardown, observer);
            } catch (SandboxException exception) {
                teardownQuietly();
                throw exception;
            } catch (DockerEngine.DockerEngineException exception) {
                teardownQuietly();
                throw failure(exception.code(), "container sandbox operation failed");
            } catch (AioClient.AioProtocolException exception) {
                Teardown postTeardown = teardownQuietly();
                if (dispatched) {
                    return result(
                            SandboxProcessStatus.UNKNOWN,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            postTeardown.confirmed(),
                            exception.code());
                }
                throw failure(exception.code(), "AIO protocol operation failed");
            } catch (RuntimeException exception) {
                Teardown postTeardown = teardownQuietly();
                if (dispatched) {
                    return result(
                            SandboxProcessStatus.UNKNOWN,
                            null,
                            new byte[0],
                            new byte[0],
                            started,
                            time.now(),
                            false,
                            false,
                            postTeardown.confirmed(),
                            "EXECUTION_OUTCOME_UNKNOWN");
                }
                throw failure("CONTAINER_SANDBOX_FAILED", "container sandbox operation failed");
            }
        }

        @Override
        public SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
            throw failure("MANAGED_PROCESS_UNSUPPORTED", "managed process sessions are not supported");
        }

        @Override
        public boolean cancel() {
            cancelled.set(true);
            if (creating) {
                return true;
            }
            if (containerId == null && containerName == null && networkName == null) {
                return true;
            }
            try {
                return teardown().confirmed();
            } catch (RuntimeException exception) {
                return false;
            }
        }

        @Override
        public void close() {
            closed.set(true);
            cancelled.set(true);
            try {
                teardown();
            } catch (RuntimeException ignored) {
                // close is an idempotent fallback; execute already reports an unconfirmed teardown.
            }
        }

        private void validateExecution(SandboxExecution execution) {
            if (!execution.workingDirectory().workspaceId().equals(workspaceId)) {
                throw failure("WORKSPACE_MISMATCH", "working directory belongs to another workspace");
            }
            if (execution.scratchSpace().isPresent()) {
                throw failure("SCRATCH_UNSUPPORTED", "container sandbox does not accept host scratch bindings");
            }
            if (execution.command().mode() == ExecutionCommandMode.SHELL) {
                if (!profile.shellAllowed()) throw failure("SHELL_DENIED", "shell execution is denied by profile");
                if (!execution.input().isEmpty()) {
                    throw failure("SHELL_INPUT_UNSUPPORTED", "shell execution does not accept piped input");
                }
                return;
            }
            String executable = execution.command().executable();
            boolean allowed =
                    profile.allowedExecutables().stream().anyMatch(value -> value.equalsIgnoreCase(executable));
            if (!allowed) throw failure("EXECUTABLE_DENIED", "executable is not allowed by profile");
            for (String argument : execution
                    .command()
                    .argv()
                    .subList(1, execution.command().argv().size())) {
                String normalized = argument.replace('\\', '/');
                if (argument.startsWith("@")
                        || normalized.equals("..")
                        || normalized.startsWith("../")
                        || normalized.contains("/../")
                        || normalized.matches("^[A-Za-z]:.*")
                        || normalized.startsWith("//")) {
                    throw failure("ARGUMENT_PATH_DENIED", "argument contains an unsafe path form");
                }
            }
        }

        private Map<String, String> environment(SandboxExecution execution) {
            Map<String, String> safe = new LinkedHashMap<>();
            execution.environment().forEach((name, value) -> {
                if (!profile.allowedEnvironmentNames().contains(name)) {
                    throw failure("ENVIRONMENT_DENIED", "environment lease contains a denied name");
                }
                safe.put(name, value);
            });
            return Map.copyOf(safe);
        }

        private String containerWorkingDirectory(SandboxExecution execution) {
            return configuration.containerPath(
                    execution.workingDirectory().projectPath().value());
        }

        private String renderCommand(SandboxExecution execution) {
            if (execution.command().mode() == ExecutionCommandMode.SHELL) {
                return execution.command().shellCommand();
            }
            StringBuilder command = new StringBuilder();
            if (!execution.input().isEmpty()) {
                command.append("printf %s '")
                        .append(Base64.getEncoder()
                                .encodeToString(execution.input().bytes()))
                        .append("' | base64 -d | ");
            }
            command.append("exec");
            for (String argument : execution.command().argv()) {
                command.append(' ').append(quote(argument));
            }
            return command.toString();
        }

        private void createNetwork() {
            networkName = networkName();
            Map<String, String> labels = labels(ROLE_NETWORK);
            Map<String, String> options = Map.of(
                    DockerAioSandboxConfiguration.GATEWAY_MODE_IPV4, GATEWAY_ISOLATED,
                    DockerAioSandboxConfiguration.GATEWAY_MODE_IPV6, GATEWAY_ISOLATED,
                    DockerAioSandboxConfiguration.ENABLE_ICC, "false");
            creating = true;
            try {
                engine().createNetwork(networkName, labels, options);
            } finally {
                creating = false;
            }
            DockerEngine.NetworkInspection inspected = engine().inspectNetwork(networkName);
            if (inspected == null
                    || !DockerAioSandboxConfiguration.NETWORK_DRIVER.equals(inspected.driver())
                    || !inspected.internal()
                    || !GATEWAY_ISOLATED.equals(
                            inspected.options().get(DockerAioSandboxConfiguration.GATEWAY_MODE_IPV4))
                    || !GATEWAY_ISOLATED.equals(
                            inspected.options().get(DockerAioSandboxConfiguration.GATEWAY_MODE_IPV6))
                    || !"false".equals(inspected.options().get(DockerAioSandboxConfiguration.ENABLE_ICC))
                    || !labels.equals(inspected.labels())) {
                throw failure(
                        "ISOLATED_NETWORK_UNAVAILABLE", "Docker did not provide the required isolated sandbox network");
            }
        }

        private void createContainer() {
            containerName = containerName();
            DockerEngine.ContainerSpec spec = new DockerEngine.ContainerSpec(
                    containerName,
                    configuration.image(),
                    labels(ROLE_CONTAINER),
                    networkName,
                    hostWorkspaceRoot.toString(),
                    configuration.containerWorkspaceRoot(),
                    environmentForContainer(),
                    configuration.nanoCpus(),
                    configuration.memoryBytes(),
                    configuration.pidsLimit(),
                    configuration.aioPort(),
                    configuration.uid(),
                    configuration.gid());
            creating = true;
            try {
                try {
                    containerId = engine().createContainer(spec);
                } catch (DockerEngine.DockerEngineException exception) {
                    DockerEngine.ContainerInspection existing = engine().inspectContainer(containerName);
                    if (existing != null) {
                        if (hasExpectedLabels(existing.labels(), ROLE_CONTAINER)) {
                            containerId = existing.id();
                            teardown();
                        } else {
                            throw failure(
                                    "CONTAINER_COLLISION",
                                    "foreign container exists with same name but non-matching labels");
                        }
                    }
                    throw failure(
                            "CONTAINER_CREATE_UNCONFIRMED",
                            "sandbox container creation result is unknown and was not executed");
                }
            } finally {
                creating = false;
            }
        }

        private Map<String, String> environmentForContainer() {
            return Map.of();
        }

        private void verifyContainer(DockerEngine.ContainerInspection inspected) {
            if (inspected == null) {
                throw failure("CONTAINER_NOT_FOUND", "sandbox container disappeared after creation");
            }
            boolean oneWorkspaceMount = inspected.mounts().size() == 1
                    && hostWorkspaceRoot
                            .toString()
                            .equals(inspected.mounts().get(0).source())
                    && configuration
                            .containerWorkspaceRoot()
                            .equals(inspected.mounts().get(0).destination())
                    && !inspected.mounts().get(0).readOnly();
            boolean isolatedNetwork = networkName.equals(inspected.networkMode())
                    && inspected.networks().keySet().equals(java.util.Set.of(networkName));
            boolean limited = inspected.nanoCpus() == configuration.nanoCpus()
                    && inspected.memoryBytes() == configuration.memoryBytes()
                    && inspected.pidsLimit() == configuration.pidsLimit();
            boolean dropped = inspected.capDrop().stream().anyMatch(value -> "ALL".equalsIgnoreCase(value));
            boolean noNewPrivileges = inspected.securityOptions().stream()
                    .anyMatch(value -> value.toLowerCase(Locale.ROOT).startsWith("no-new-privileges"));
            boolean userMatches = configuration.uid().isEmpty()
                    || (configuration.uid().getAsInt() + ":"
                                    + configuration.gid().getAsInt())
                            .equals(inspected.user());
            Map<String, String> expectedLabels = labels(ROLE_CONTAINER);
            boolean labelsPresent = inspected.labels().entrySet().containsAll(expectedLabels.entrySet());
            if (!configuration.image().equals(inspected.image())
                    || !labelsPresent
                    || !oneWorkspaceMount
                    || !isolatedNetwork
                    || !limited
                    || !dropped
                    || !noNewPrivileges
                    || inspected.privileged()
                    || !userMatches
                    || inspected.managementPort() < 1) {
                throw failure("CONTAINER_IDENTITY_MISMATCH", "sandbox container identity or isolation drifted");
            }
        }

        private SandboxProcessResult resultFromExec(
                AioClient.ExecResult exec,
                SandboxExecution execution,
                Instant started,
                Teardown completedTeardown,
                ExecutionOutputObserver observer) {
            SandboxProcessStatus status = status(exec);
            Integer exitCode = status == SandboxProcessStatus.EXITED ? exec.exitCode() : null;
            BoundedOutputBuffer stdoutBuffer =
                    new BoundedOutputBuffer(execution.limits().maxStdoutBytes());
            BoundedOutputBuffer stderrBuffer =
                    new BoundedOutputBuffer(execution.limits().maxStderrBytes());
            stdoutBuffer.write(exec.stdout());
            stderrBuffer.write(exec.stderr());
            boolean stdoutTruncated = exec.stdoutTruncated() || stdoutBuffer.truncated();
            boolean stderrTruncated = exec.stderrTruncated() || stderrBuffer.truncated();
            String failureCode = failureCode(status, completedTeardown.confirmed(), exec.status());
            if (!completedTeardown.confirmed()) {
                status = SandboxProcessStatus.UNKNOWN;
                exitCode = null;
                failureCode = "CONTAINER_TEARDOWN_UNCONFIRMED";
            }
            SandboxProcessResult result = result(
                    status,
                    exitCode,
                    stdoutBuffer.bytes(),
                    stderrBuffer.bytes(),
                    started,
                    time.now(),
                    stdoutTruncated,
                    stderrTruncated,
                    completedTeardown.confirmed(),
                    failureCode);
            notifyObserver(observer, result, stdoutTruncated, stderrTruncated);
            return result;
        }

        private SandboxProcessStatus status(AioClient.ExecResult exec) {
            return switch (exec.status()) {
                case "completed" ->
                    exec.exitCode() == null ? SandboxProcessStatus.UNKNOWN : SandboxProcessStatus.EXITED;
                case "timed_out" -> SandboxProcessStatus.TIMED_OUT;
                case "killed" -> cancelled.get() ? SandboxProcessStatus.CANCELLED : SandboxProcessStatus.UNKNOWN;
                case "running", "pending" ->
                    cancelled.get() ? SandboxProcessStatus.CANCELLED : SandboxProcessStatus.TIMED_OUT;
                default -> SandboxProcessStatus.UNKNOWN;
            };
        }

        private String failureCode(SandboxProcessStatus status, boolean teardownConfirmed, String aioStatus) {
            return switch (status) {
                case EXITED -> null;
                case TIMED_OUT -> teardownConfirmed ? "TIMEOUT" : "TIMEOUT_TREE_UNCONFIRMED";
                case CANCELLED -> "CANCELLED";
                case UNKNOWN -> "completed".equals(aioStatus) ? "AIO_EXIT_CODE_UNKNOWN" : "EXECUTION_OUTCOME_UNKNOWN";
                default -> "EXECUTION_OUTCOME_UNKNOWN";
            };
        }

        private Teardown teardown() {
            synchronized (teardownLock) {
                Teardown existing = teardown;
                if (existing != null && existing.confirmed()) return existing;
                if (aioSessionId != null && managementUri != null) {
                    aio().kill(managementUri, aioSessionId, configuration.stopTimeout());
                    aio().closeSession(managementUri, aioSessionId, configuration.stopTimeout());
                }
                boolean containerGone = true;
                if (containerId != null) {
                    try {
                        engine().stopContainer(containerId, configuration.stopTimeout());
                    } catch (RuntimeException ignored) {
                        // A created-but-not-running container still has to be removed below.
                    }
                    try {
                        engine().removeContainer(containerId);
                    } catch (RuntimeException exception) {
                        containerGone = false;
                    }
                    try {
                        containerGone = containerGone && engine().inspectContainer(containerId) == null;
                    } catch (RuntimeException exception) {
                        containerGone = false;
                    }
                } else if (containerName != null) {
                    DockerEngine.ContainerInspection inspected = null;
                    try {
                        inspected = engine().inspectContainer(containerName);
                    } catch (RuntimeException ignored) {
                    }
                    if (inspected != null && !hasExpectedLabels(inspected.labels(), ROLE_CONTAINER)) {
                        // Refuse to remove a foreign container with matching name but non-matching labels
                        containerGone = false;
                    } else if (inspected != null) {
                        try {
                            engine().stopContainer(inspected.id(), configuration.stopTimeout());
                        } catch (RuntimeException ignored) {
                        }
                        try {
                            engine().removeContainer(inspected.id());
                        } catch (RuntimeException exception) {
                            containerGone = false;
                        }
                        try {
                            containerGone = containerGone && engine().inspectContainer(containerName) == null;
                        } catch (RuntimeException exception) {
                            containerGone = false;
                        }
                    }
                }
                boolean networkGone = true;
                if (networkName != null) {
                    DockerEngine.NetworkInspection inspected = null;
                    try {
                        inspected = engine().inspectNetwork(networkName);
                    } catch (RuntimeException ignored) {
                    }
                    if (inspected != null && !hasExpectedLabels(inspected.labels(), ROLE_NETWORK)) {
                        // Refuse to touch foreign network
                        networkGone = false;
                    } else if (inspected != null) {
                        try {
                            engine().removeNetwork(networkName);
                            networkGone = engine().inspectNetwork(networkName) == null;
                        } catch (RuntimeException exception) {
                            networkGone = false;
                        }
                    }
                }
                Teardown completed = new Teardown(containerGone && networkGone, containerGone, networkGone);
                if (completed.confirmed()) teardown = completed;
                return completed;
            }
        }

        private Teardown teardownQuietly() {
            try {
                return teardown();
            } catch (RuntimeException ignored) {
                return new Teardown(false, false, false);
            }
        }

        private boolean hasExpectedLabels(Map<String, String> labels, String role) {
            if (labels == null) return false;
            Map<String, String> expected = labels(role);
            return labels.entrySet().containsAll(expected.entrySet());
        }

        private String networkName() {
            return "haifa-" + installationToken() + "-" + sessionToken() + "-net";
        }

        private String containerName() {
            return "haifa-" + installationToken() + "-" + sessionToken();
        }

        private String installationToken() {
            return token(configuration.installationId());
        }

        private String sessionToken() {
            return token(id.value());
        }

        private Map<String, String> labels(String role) {
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(LABEL_INSTALLATION, configuration.installationId());
            labels.put(LABEL_SESSION, id.value());
            labels.put(LABEL_ROLE, role);
            labels.put(LABEL_MANAGED, "true");
            return Map.copyOf(labels);
        }
    }

    private static String token(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        if (normalized.length() > 48) normalized = normalized.substring(0, 48);
        if (normalized.isEmpty() || !normalized.matches("^[a-z0-9].*")) normalized = "x" + normalized;
        return normalized;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void notifyObserver(
            ExecutionOutputObserver observer,
            SandboxProcessResult result,
            boolean stdoutTruncated,
            boolean stderrTruncated) {
        try {
            observer.onOutput(
                    new ProcessOutputChunk(ExecutionOutputChannel.STDOUT, result.stdout(), true, stdoutTruncated));
            observer.onOutput(
                    new ProcessOutputChunk(ExecutionOutputChannel.STDERR, result.stderr(), true, stderrTruncated));
        } catch (RuntimeException ignored) {
            // Display observers never change the authoritative execution result.
        }
    }

    private static Duration reserve(Duration timeout) {
        Duration quarter = timeout.dividedBy(4);
        Duration reserve = quarter.compareTo(MINIMUM_CLEANUP_RESERVE) < 0 ? MINIMUM_CLEANUP_RESERVE : quarter;
        if (reserve.compareTo(Duration.ofSeconds(15)) > 0) reserve = Duration.ofSeconds(15);
        return reserve;
    }

    private static Duration remaining(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining < Duration.ofMillis(1).toNanos()) {
            throw new SandboxException("DEADLINE_EXCEEDED", "sandbox execution deadline expired");
        }
        return Duration.ofNanos(remaining);
    }

    private static SandboxProcessResult result(
            SandboxProcessStatus status,
            Integer exitCode,
            byte[] stdout,
            byte[] stderr,
            Instant started,
            Instant ended,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean treeTerminated,
            String failureCode) {
        return new SandboxProcessResult(
                status,
                exitCode,
                stdout,
                stderr,
                started,
                ended,
                stdoutTruncated,
                stderrTruncated,
                treeTerminated,
                0,
                false,
                false,
                failureCode,
                false);
    }

    private static SandboxException failure(String code, String message) {
        return new SandboxException(code, message);
    }

    private record Teardown(boolean confirmed, boolean containerGone, boolean networkGone) {}
}
