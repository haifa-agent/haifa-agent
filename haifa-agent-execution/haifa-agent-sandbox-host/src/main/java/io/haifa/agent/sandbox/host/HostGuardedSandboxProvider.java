package io.haifa.agent.sandbox.host;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class HostGuardedSandboxProvider implements SandboxProvider {
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AZURE_CLIENT_SECRET",
            "GOOGLE_APPLICATION_CREDENTIALS");

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public HostGuardedSandboxProvider(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public String providerId() {
        return "host-guarded";
    }

    @Override
    public SandboxCapabilities capabilities() {
        return new SandboxCapabilities(true, false, false, false, false);
    }

    @Override
    public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(mount, "mount must not be null");
        var workspace = workspaces
                .find(mount.workspaceId())
                .orElseThrow(() -> failure("WORKSPACE_NOT_FOUND", "workspace not found"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) throw failure("WORKSPACE_INACTIVE", "workspace is inactive");
        var binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> failure("BINDING_NOT_FOUND", "workspace binding not found"));
        if (binding.status() != WorkspaceBindingStatus.ACTIVE) throw failure("BINDING_INACTIVE", "binding is inactive");
        if (mount.readOnly() || binding.mode() == WorkspaceBindingMode.READ_ONLY) {
            throw failure("READ_ONLY_UNENFORCEABLE", "host provider cannot safely execute against a read-only mount");
        }
        if (!binding.permissions().allows(WorkspacePermission.EXECUTE)
                || !binding.capabilities().allows("execution.run")) {
            throw failure("EXECUTION_DENIED", "workspace execution capability is denied");
        }
        if (profile.networkPolicy() == NetworkPolicy.DENY) {
            throw failure("NETWORK_ISOLATION_UNAVAILABLE", "host provider cannot guarantee network denial");
        }
        try {
            Path root =
                    locations.resolveForTrustedProvider(binding.locationRef()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!LocalWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint()) || isLink(root)) {
                throw failure("ROOT_CHANGED", "workspace root identity changed");
            }
            return new Session(new SandboxSessionId(identifiers.nextValue()), profile, workspace.id(), root);
        } catch (IOException exception) {
            throw failure("ROOT_UNAVAILABLE", "workspace root is unavailable");
        }
    }

    private final class Session implements SandboxSession {
        private final SandboxSessionId id;
        private final SandboxProfile profile;
        private final io.haifa.agent.project.workspace.WorkspaceId workspaceId;
        private final Path root;
        private volatile Process current;
        private volatile boolean cancelRequested;
        private volatile boolean closed;

        private Session(
                SandboxSessionId id,
                SandboxProfile profile,
                io.haifa.agent.project.workspace.WorkspaceId workspaceId,
                Path root) {
            this.id = id;
            this.profile = profile;
            this.workspaceId = workspaceId;
            this.root = root;
        }

        @Override
        public SandboxSessionId id() {
            return id;
        }

        @Override
        public synchronized SandboxProcessResult execute(SandboxExecution execution) {
            if (closed) throw failure("SESSION_CLOSED", "sandbox session is closed");
            if (!execution.workingDirectory().workspaceId().equals(workspaceId)) {
                throw failure("WORKSPACE_MISMATCH", "working directory belongs to another workspace");
            }
            validateCommand(execution);
            Path cwd = resolveDirectory(execution.workingDirectory());
            Map<String, String> environment = validateEnvironment(execution.environment());
            Instant started = time.now();
            try {
                cancelRequested = false;
                ProcessBuilder builder = new ProcessBuilder(execution.command().argv());
                builder.directory(cwd.toFile());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                builder.environment().clear();
                builder.environment().putAll(environment);
                Process process = builder.start();
                current = process;
                process.getOutputStream().close();
                var stdout = CompletableFuture.supplyAsync(
                        () -> read(process.getInputStream(), execution.limits().maxStdoutBytes()));
                var stderr = CompletableFuture.supplyAsync(
                        () -> read(process.getErrorStream(), execution.limits().maxStderrBytes()));
                WaitOutcome outcome = waitFor(
                        process,
                        execution.limits().timeout(),
                        execution.limits().maxProcesses());
                SandboxProcessStatus status;
                boolean treeTerminated = true;
                Integer exitCode = null;
                if (cancelRequested) {
                    treeTerminated = terminateTree(process);
                    status = treeTerminated ? SandboxProcessStatus.CANCELLED : SandboxProcessStatus.UNKNOWN;
                } else if (outcome == WaitOutcome.FINISHED) {
                    exitCode = process.exitValue();
                    status = SandboxProcessStatus.EXITED;
                } else {
                    treeTerminated = terminateTree(process);
                    status = outcome == WaitOutcome.PROCESS_LIMIT_EXCEEDED
                            ? SandboxProcessStatus.UNKNOWN
                            : treeTerminated ? SandboxProcessStatus.TIMED_OUT : SandboxProcessStatus.UNKNOWN;
                }
                BoundedBytes out = stdout.get(5, TimeUnit.SECONDS);
                BoundedBytes err = stderr.get(5, TimeUnit.SECONDS);
                Instant ended = time.now();
                return new SandboxProcessResult(
                        status,
                        exitCode,
                        out.bytes(),
                        err.bytes(),
                        started,
                        ended,
                        out.truncated(),
                        err.truncated(),
                        treeTerminated,
                        observedProcesses(process));
            } catch (HostSandboxException exception) {
                throw exception;
            } catch (Exception exception) {
                return new SandboxProcessResult(
                        SandboxProcessStatus.UNKNOWN,
                        null,
                        new byte[0],
                        new byte[0],
                        started,
                        time.now(),
                        false,
                        false,
                        current == null || !current.isAlive(),
                        current == null ? 0 : observedProcesses(current));
            } finally {
                current = null;
            }
        }

        @Override
        public boolean cancel() {
            cancelRequested = true;
            Process process = current;
            return process == null || terminateTree(process);
        }

        private WaitOutcome waitFor(Process process, Duration timeout, int maxProcesses) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                if (cancelRequested) return WaitOutcome.CANCELLED;
                if (observedProcesses(process) > maxProcesses) return WaitOutcome.PROCESS_LIMIT_EXCEEDED;
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return WaitOutcome.TIMED_OUT;
                long waitMillis = Math.max(1, Math.min(20, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            }
            return WaitOutcome.FINISHED;
        }

        @Override
        public void close() {
            cancel();
            closed = true;
        }

        private void validateCommand(SandboxExecution execution) {
            if (execution.command().mode() == ExecutionCommandMode.SHELL && !profile.shellAllowed()) {
                throw failure("SHELL_DENIED", "shell execution is denied by profile");
            }
            String executable = execution.command().executable();
            if (executable.contains("/") || executable.contains("\\") || executable.contains(":")) {
                throw failure("EXECUTABLE_PATH_DENIED", "executable must be selected by approved name");
            }
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

        private Map<String, String> validateEnvironment(Map<String, String> requested) {
            var safe = new java.util.HashMap<String, String>();
            requested.forEach((name, value) -> {
                String upper = name.toUpperCase(Locale.ROOT);
                if (!profile.allowedEnvironmentNames().contains(name) || FORBIDDEN_ENVIRONMENT.contains(upper)) {
                    throw failure("ENVIRONMENT_DENIED", "environment lease contains a denied name");
                }
                safe.put(name, value);
            });
            return Map.copyOf(safe);
        }

        private Path resolveDirectory(WorkspacePath logical) {
            Path currentPath = root;
            for (String segment : logical.projectPath().segments()) {
                currentPath = currentPath.resolve(segment).normalize();
                if (!currentPath.startsWith(root)
                        || !Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS)
                        || isLink(currentPath)) {
                    throw failure("CWD_DENIED", "working directory is unavailable or unsafe");
                }
            }
            return currentPath;
        }
    }

    private static BoundedBytes read(InputStream input, int maximum) {
        try (input;
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            boolean truncated = false;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = maximum - total;
                if (remaining > 0) {
                    int accepted = Math.min(remaining, count);
                    output.write(buffer, 0, accepted);
                    total += accepted;
                    if (accepted < count) truncated = true;
                } else {
                    truncated = true;
                }
            }
            return new BoundedBytes(output.toByteArray(), truncated);
        } catch (IOException exception) {
            return new BoundedBytes(new byte[0], true);
        }
    }

    private static boolean terminateTree(Process process) {
        List<ProcessHandle> descendants =
                new ArrayList<>(process.toHandle().descendants().toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static int observedProcesses(Process process) {
        return 1 + Math.toIntExact(process.toHandle().descendants().limit(63).count());
    }

    private static boolean isLink(Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther()) return true;
            return Boolean.TRUE.equals(Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return false;
        } catch (IOException exception) {
            return true;
        }
    }

    private static HostSandboxException failure(String code, String message) {
        return new HostSandboxException(code, message);
    }

    private record BoundedBytes(byte[] bytes, boolean truncated) {}

    private enum WaitOutcome {
        FINISHED,
        TIMED_OUT,
        CANCELLED,
        PROCESS_LIMIT_EXCEEDED
    }
}
