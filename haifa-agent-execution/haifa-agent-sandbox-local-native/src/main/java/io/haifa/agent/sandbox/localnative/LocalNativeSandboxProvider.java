package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
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
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxManagedProcess;
import io.haifa.agent.sandbox.api.SandboxPreflight;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.api.WorkspaceMount;
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

public final class LocalNativeSandboxProvider implements SandboxProvider {
    public static final String PROVIDER_ID = "local-native";
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "SSH_AUTH_SOCK",
            "DOCKER_HOST",
            "KUBECONFIG",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AZURE_CLIENT_SECRET",
            "GOOGLE_APPLICATION_CREDENTIALS");
    private static final Set<String> PROVIDER_MANAGED_ENVIRONMENT = Set.of(
            "HOME",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH",
            "XDG_CONFIG_HOME",
            "XDG_DATA_HOME",
            "XDG_CACHE_HOME",
            "XDG_STATE_HOME",
            "TMPDIR",
            "TMP",
            "TEMP",
            "GOTMPDIR",
            "GOCACHE");

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final LocalNativeSandboxConfiguration configuration;
    private final LocalNativeAdapter adapter;

    public LocalNativeSandboxProvider(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            LocalNativeSandboxConfiguration configuration) {
        this(workspaces, bindings, locations, identifiers, time, configuration, LocalNativeAdapters.system());
    }

    LocalNativeSandboxProvider(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            LocalNativeSandboxConfiguration configuration,
            LocalNativeAdapter adapter) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public SandboxCapabilities capabilities() {
        return new SandboxCapabilities(true, true, true, false, false);
    }

    @Override
    public SandboxConfigurationDigest configurationDigest() {
        return configuration.digest();
    }

    @Override
    public SandboxPreflight preflight(SandboxProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (!providerId().equals(profile.providerId())) {
            throw failure("CAPABILITY_UNAVAILABLE", "sandbox provider binding does not match");
        }
        if (!configurationDigest().equals(profile.providerConfigurationDigest())) {
            throw failure("CAPABILITY_UNAVAILABLE", "sandbox provider configuration does not match");
        }
        if (!profile.filesystemPolicy().sensitivePathsDenied()) {
            throw failure("CAPABILITY_UNAVAILABLE", "local-native requires sensitive path denial");
        }
        configuration.resolveAdditionalPaths(profile.filesystemPolicy().additionalPathPolicyRefs());
        adapter.preflight(configuration);
        SandboxCapabilities effective =
                new SandboxCapabilities(true, true, profile.networkPolicy() == NetworkPolicy.DENY, false, false);
        if (!effective.satisfies(profile.requiredCapabilities())) {
            String code = profile.networkPolicy() == NetworkPolicy.DENY && !effective.networkIsolation()
                    ? "NETWORK_POLICY_UNENFORCEABLE"
                    : "CAPABILITY_UNAVAILABLE";
            throw failure(code, "local-native cannot satisfy the required capabilities");
        }
        return new SandboxPreflight(providerId(), adapter.adapterId(), configurationDigest(), effective, false);
    }

    @Override
    public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(mount, "mount must not be null");
        preflight(profile);
        boolean profileReadOnly = profile.filesystemPolicy().workspaceAccess() == SandboxWorkspaceAccess.READ_ONLY;
        if (mount.readOnly() != profileReadOnly) {
            throw failure("WORKSPACE_BIND_FAILED", "workspace mount does not match the frozen profile");
        }
        var workspace = workspaces
                .find(mount.workspaceId())
                .orElseThrow(() -> failure("WORKSPACE_BIND_FAILED", "workspace is unavailable"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) {
            throw failure("WORKSPACE_BIND_FAILED", "workspace is inactive");
        }
        var binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> failure("WORKSPACE_BIND_FAILED", "workspace binding is unavailable"));
        if (binding.status() != WorkspaceBindingStatus.ACTIVE
                || !binding.permissions().allows(WorkspacePermission.EXECUTE)
                || !binding.capabilities().allows("execution.run")) {
            throw failure("WORKSPACE_BIND_FAILED", "workspace execution is unavailable");
        }
        if (!profileReadOnly && binding.mode() == WorkspaceBindingMode.READ_ONLY) {
            throw failure("WORKSPACE_BIND_FAILED", "workspace binding cannot satisfy write access");
        }
        try {
            Path root =
                    locations.resolveForTrustedProvider(binding.locationRef()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (isLink(root)
                    || !LocalWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint())) {
                throw failure("WORKSPACE_BIND_FAILED", "workspace root identity changed");
            }
            List<LocalNativePathGrant> additional = configuration.resolveAdditionalPaths(
                    profile.filesystemPolicy().additionalPathPolicyRefs());
            validatePathBoundaries(root, additional);
            return new Session(
                    new SandboxSessionId(identifiers.nextValue()), profile, workspace.id(), root, additional);
        } catch (IOException exception) {
            throw failure("WORKSPACE_BIND_FAILED", "workspace root is unavailable");
        }
    }

    private void validatePathBoundaries(Path workspaceRoot, List<LocalNativePathGrant> additional) {
        for (Path sensitive : configuration.sensitivePaths()) {
            if (overlaps(workspaceRoot, sensitive)) {
                throw failure("WORKSPACE_BIND_FAILED", "workspace overlaps a sensitive path");
            }
        }
        for (LocalNativePathGrant grant : additional) {
            if (!Files.exists(grant.path(), LinkOption.NOFOLLOW_LINKS) || isLink(grant.path())) {
                throw failure("WORKSPACE_BIND_FAILED", "additional path is unavailable or unsafe");
            }
            if (overlaps(workspaceRoot, grant.path())
                    || configuration.sensitivePaths().stream()
                            .anyMatch(sensitive -> overlaps(grant.path(), sensitive))) {
                throw failure("WORKSPACE_BIND_FAILED", "additional path conflicts with protected paths");
            }
        }
    }

    private final class Session implements SandboxSession {
        private final SandboxSessionId id;
        private final SandboxProfile profile;
        private final io.haifa.agent.project.workspace.WorkspaceId workspaceId;
        private final Path root;
        private final List<LocalNativePathGrant> additionalPaths;
        private volatile Process current;
        private volatile Path controlDirectory;
        private volatile boolean cancelRequested;
        private boolean executed;
        private boolean closed;

        private Session(
                SandboxSessionId id,
                SandboxProfile profile,
                io.haifa.agent.project.workspace.WorkspaceId workspaceId,
                Path root,
                List<LocalNativePathGrant> additionalPaths) {
            this.id = id;
            this.profile = profile;
            this.workspaceId = workspaceId;
            this.root = root;
            this.additionalPaths = additionalPaths;
        }

        @Override
        public SandboxSessionId id() {
            return id;
        }

        @Override
        public synchronized SandboxProcessResult execute(SandboxExecution execution) {
            return execute(execution, ExecutionOutputObserver.noop());
        }

        @Override
        public synchronized SandboxProcessResult execute(SandboxExecution execution, ExecutionOutputObserver observer) {
            Objects.requireNonNull(execution, "execution must not be null");
            Objects.requireNonNull(observer, "observer must not be null");
            if (closed) throw failure("SANDBOX_START_FAILED", "sandbox session is closed");
            if (executed) throw failure("SANDBOX_START_FAILED", "local-native session is single-use");
            executed = true;
            if (!execution.workingDirectory().workspaceId().equals(workspaceId)) {
                throw failure("WORKSPACE_BIND_FAILED", "working directory belongs to another workspace");
            }
            validateCommand(execution);
            validateRequestedEnvironment(execution.environment());
            Path cwd = resolveDirectory(execution.workingDirectory());
            Instant started = time.now();
            if (cancelRequested) {
                return new SandboxProcessResult(
                        SandboxProcessStatus.CANCELLED,
                        null,
                        new byte[0],
                        new byte[0],
                        started,
                        time.now(),
                        false,
                        false,
                        true,
                        0,
                        false,
                        false);
            }
            Path controls = createControlDirectory();
            controlDirectory = controls;
            Map<String, String> environment;
            LocalNativeLaunchPlan plan;
            try {
                environment = validateEnvironment(execution.environment(), controls, execution.scratchSpace());
                plan = adapter.prepare(
                        configuration,
                        profile,
                        root,
                        cwd,
                        controls,
                        additionalPaths,
                        execution.scratchSpace(),
                        execution.command());
            } catch (RuntimeException exception) {
                cleanupControlDirectory(controls);
                controlDirectory = null;
                throw exception;
            }
            SandboxProcessResult result;
            Thread shutdownHook = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(plan.argv());
                builder.directory(cwd.toFile());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                builder.environment().clear();
                builder.environment().putAll(environment);
                Process process = builder.start();
                current = process;
                observer.onStarted();
                shutdownHook = registerShutdownHook(process);
                try (var standardInput = process.getOutputStream()) {
                    standardInput.write(execution.input().bytes());
                }
                var outputLimitExceeded = new java.util.concurrent.atomic.AtomicBoolean();
                CompletableFuture<BoundedBytes> stdout = CompletableFuture.supplyAsync(() -> read(
                        process.getInputStream(),
                        execution.limits().maxStdoutBytes(),
                        ExecutionOutputChannel.STDOUT,
                        observer,
                        execution.limits().outputOverflowPolicy(),
                        outputLimitExceeded));
                CompletableFuture<BoundedBytes> stderr = CompletableFuture.supplyAsync(() -> read(
                        process.getErrorStream(),
                        execution.limits().maxStderrBytes(),
                        ExecutionOutputChannel.STDERR,
                        observer,
                        execution.limits().outputOverflowPolicy(),
                        outputLimitExceeded));
                WaitOutcome outcome = waitFor(
                        process,
                        execution.limits().timeout(),
                        execution.limits().maxProcesses(),
                        outputLimitExceeded);
                boolean treeTerminated = true;
                Integer exitCode = null;
                SandboxProcessStatus status;
                if (cancelRequested) {
                    treeTerminated = terminateTree(process);
                    status = treeTerminated ? SandboxProcessStatus.CANCELLED : SandboxProcessStatus.UNKNOWN;
                } else if (outcome == WaitOutcome.FINISHED) {
                    exitCode = process.exitValue();
                    status = SandboxProcessStatus.EXITED;
                } else {
                    treeTerminated = terminateTree(process);
                    status = outcome == WaitOutcome.OUTPUT_LIMIT_EXCEEDED && treeTerminated
                            ? SandboxProcessStatus.OUTPUT_LIMIT_EXCEEDED
                            : outcome == WaitOutcome.TIMED_OUT && treeTerminated
                                    ? SandboxProcessStatus.TIMED_OUT
                                    : SandboxProcessStatus.UNKNOWN;
                }
                BoundedBytes out = stdout.get(5, TimeUnit.SECONDS);
                BoundedBytes err = stderr.get(5, TimeUnit.SECONDS);
                if (outputLimitExceeded.get()
                        && execution.limits().outputOverflowPolicy()
                                == io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE
                        && status == SandboxProcessStatus.EXITED) {
                    status = SandboxProcessStatus.OUTPUT_LIMIT_EXCEEDED;
                    exitCode = null;
                }
                result = new SandboxProcessResult(
                        status,
                        exitCode,
                        out.bytes(),
                        err.bytes(),
                        started,
                        time.now(),
                        out.truncated(),
                        err.truncated(),
                        treeTerminated,
                        observedProcesses(process),
                        true,
                        false);
            } catch (IOException exception) {
                cleanupControlDirectory(controls);
                controlDirectory = null;
                throw failure("SANDBOX_START_FAILED", "sandboxed process could not be started");
            } catch (LocalNativeSandboxException exception) {
                cleanupControlDirectory(controls);
                controlDirectory = null;
                throw exception;
            } catch (Exception exception) {
                boolean terminated = current == null || terminateTree(current);
                result = new SandboxProcessResult(
                        SandboxProcessStatus.UNKNOWN,
                        null,
                        new byte[0],
                        new byte[0],
                        started,
                        time.now(),
                        false,
                        false,
                        terminated,
                        current == null ? 0 : observedProcesses(current),
                        true,
                        false);
            } finally {
                removeShutdownHook(shutdownHook);
                current = null;
                cancelRequested = false;
            }
            boolean cleaned = cleanupControlDirectory(controls);
            if (!cleaned) {
                result = new SandboxProcessResult(
                        SandboxProcessStatus.UNKNOWN,
                        result.exitCode(),
                        result.stdout(),
                        result.stderr(),
                        result.startedAt(),
                        result.endedAt(),
                        result.stdoutTruncated(),
                        result.stderrTruncated(),
                        result.processTreeTerminated(),
                        result.observedProcessCount(),
                        result.scratchProvisioned(),
                        true);
            }
            controlDirectory = null;
            return result;
        }

        @Override
        public SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
            throw failure("CAPABILITY_UNAVAILABLE", "local-native managed processes are not supported");
        }

        @Override
        public boolean cancel() {
            cancelRequested = true;
            Process process = current;
            return process == null || terminateTree(process);
        }

        @Override
        public synchronized void close() {
            cancel();
            closed = true;
            Path controls = controlDirectory;
            if (controls != null && !cleanupControlDirectory(controls)) {
                throw failure("SANDBOX_CLEANUP_FAILED", "sandbox control resources could not be removed");
            }
            controlDirectory = null;
        }

        private void validateCommand(SandboxExecution execution) {
            if (execution.command().mode() == ExecutionCommandMode.SHELL) {
                if (!profile.shellAllowed()) throw failure("CAPABILITY_UNAVAILABLE", "shell execution is denied");
                return;
            }
            String executable = execution.command().executable();
            boolean allowed =
                    profile.allowedExecutables().stream().anyMatch(value -> value.equalsIgnoreCase(executable));
            if (!allowed) throw failure("CAPABILITY_UNAVAILABLE", "executable is denied by the profile");
        }

        private Path resolveDirectory(WorkspacePath logical) {
            Path currentPath = root;
            for (String segment : logical.projectPath().segments()) {
                currentPath = currentPath.resolve(segment).normalize();
                if (!currentPath.startsWith(root)
                        || !Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS)
                        || isLink(currentPath)) {
                    throw failure("WORKSPACE_BIND_FAILED", "working directory is unavailable or unsafe");
                }
            }
            return currentPath;
        }

        private Map<String, String> validateEnvironment(
                Map<String, String> requested, Path controls, ExecutionScratchSpaceSpec scratchSpace) {
            var safe = new java.util.LinkedHashMap<String, String>();
            validateRequestedEnvironment(requested);
            safe.putAll(requested);
            if (adapter.adapterId().equals("linux-bubblewrap")) {
                safe.put("HOME", "/tmp/haifa-home");
                scratchSpace.rootEnvironmentNames().forEach(name -> safe.put(name, "/tmp"));
                scratchSpace
                        .childBindings()
                        .forEach(binding -> safe.put(binding.environmentName(), "/tmp/" + binding.relativeDirectory()));
            } else {
                Path home = controls.resolve("home");
                try {
                    Files.createDirectory(home);
                    SecureFilePermissions.secureDirectory(home);
                } catch (IOException exception) {
                    throw failure("SANDBOX_ISOLATED_HOME_UNAVAILABLE", "sandbox home could not be created");
                }
                safe.put("HOME", home.toString());
                Path scratchRoot = provisionScratch(controls, scratchSpace);
                scratchSpace.rootEnvironmentNames().forEach(name -> safe.put(name, scratchRoot.toString()));
                scratchSpace
                        .childBindings()
                        .forEach(binding -> safe.put(
                                binding.environmentName(),
                                scratchRoot.resolve(binding.relativeDirectory()).toString()));
            }
            return Map.copyOf(safe);
        }

        private void validateRequestedEnvironment(Map<String, String> requested) {
            requested.forEach((name, value) -> {
                String upper = name.toUpperCase(Locale.ROOT);
                if (!profile.allowedEnvironmentNames().contains(name)
                        || FORBIDDEN_ENVIRONMENT.contains(upper)
                        || PROVIDER_MANAGED_ENVIRONMENT.contains(upper)
                        || looksLikeSecretName(upper)) {
                    throw failure("CAPABILITY_UNAVAILABLE", "environment lease contains a denied name");
                }
            });
        }

        private Path provisionScratch(Path controls, ExecutionScratchSpaceSpec scratchSpace) {
            Path scratchRoot = controls.resolve("tmp");
            try {
                Files.createDirectory(scratchRoot);
                SecureFilePermissions.secureDirectory(scratchRoot);
                for (var binding : scratchSpace.childBindings()) {
                    Path current = scratchRoot;
                    for (String segment : binding.relativeDirectory().split("/")) {
                        current = current.resolve(segment);
                        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                            Files.createDirectory(current);
                        }
                        SecureFilePermissions.secureDirectory(current);
                    }
                    if (!current.normalize().startsWith(scratchRoot) || isLink(current) || !Files.isWritable(current)) {
                        throw new IOException("scratch child is unsafe");
                    }
                }
                if (!Files.isWritable(scratchRoot)) throw new IOException("scratch root is not writable");
                return scratchRoot;
            } catch (IOException exception) {
                throw failure("SANDBOX_PROVISION_FAILED", "sandbox scratch space could not be created");
            }
        }

        private WaitOutcome waitFor(
                Process process,
                Duration timeout,
                int maxProcesses,
                java.util.concurrent.atomic.AtomicBoolean outputLimitExceeded)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                if (cancelRequested) return WaitOutcome.CANCELLED;
                if (outputLimitExceeded.get()) return WaitOutcome.OUTPUT_LIMIT_EXCEEDED;
                if (observedProcesses(process) > maxProcesses) return WaitOutcome.PROCESS_LIMIT_EXCEEDED;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return WaitOutcome.TIMED_OUT;
                process.waitFor(
                        Math.max(1, Math.min(20, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            return WaitOutcome.FINISHED;
        }
    }

    private Path createControlDirectory() {
        try {
            Files.createDirectories(configuration.controlRoot());
            SecureFilePermissions.secureDirectory(configuration.controlRoot());
            Path directory = Files.createTempDirectory(configuration.controlRoot(), "session-");
            SecureFilePermissions.secureDirectory(directory);
            return directory;
        } catch (IOException exception) {
            throw failure("SANDBOX_PROVISION_FAILED", "sandbox control directory could not be created");
        }
    }

    private boolean cleanupControlDirectory(Path directory) {
        if (directory == null) return true;
        Path root = configuration.controlRoot().toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) return false;
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return false;
        }
    }

    private static BoundedBytes read(
            InputStream input,
            int maximum,
            ExecutionOutputChannel channel,
            ExecutionOutputObserver observer,
            io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy overflowPolicy,
            java.util.concurrent.atomic.AtomicBoolean outputLimitExceeded) {
        try (input) {
            var output = new io.haifa.agent.execution.api.BoundedOutputBuffer(maximum);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                byte[] chunk = java.util.Arrays.copyOf(buffer, count);
                output.write(chunk);
                if (output.truncated()
                        && overflowPolicy == io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE) {
                    outputLimitExceeded.set(true);
                }
                notifyObserver(
                        observer, new io.haifa.agent.execution.api.ProcessOutputChunk(channel, chunk, false, false));
            }
            notifyObserver(
                    observer,
                    new io.haifa.agent.execution.api.ProcessOutputChunk(
                            channel, new byte[0], true, output.truncated()));
            return new BoundedBytes(output.bytes(), output.truncated());
        } catch (IOException exception) {
            notifyObserver(
                    observer, new io.haifa.agent.execution.api.ProcessOutputChunk(channel, new byte[0], true, true));
            return new BoundedBytes(new byte[0], true);
        }
    }

    private static void notifyObserver(
            ExecutionOutputObserver observer, io.haifa.agent.execution.api.ProcessOutputChunk chunk) {
        try {
            observer.onOutput(chunk);
        } catch (RuntimeException ignored) {
            // Presentation failure cannot block process drainage or cleanup.
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

    private static Thread registerShutdownHook(Process process) {
        Thread hook = new Thread(() -> terminateTree(process), "haifa-local-native-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return hook;
        } catch (IllegalStateException | SecurityException exception) {
            terminateTree(process);
            throw failure("SANDBOX_PROVISION_FAILED", "sandbox shutdown cleanup could not be registered");
        }
    }

    private static void removeShutdownHook(Thread hook) {
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM shutdown owns the hook now.
        }
    }

    private static int observedProcesses(Process process) {
        return 1 + Math.toIntExact(process.toHandle().descendants().limit(63).count());
    }

    private static boolean looksLikeSecretName(String upperName) {
        return upperName.contains("API_KEY")
                || upperName.contains("ACCESS_KEY")
                || upperName.contains("PRIVATE_KEY")
                || upperName.contains("PASSWORD")
                || upperName.contains("SECRET")
                || upperName.contains("TOKEN")
                || upperName.contains("CREDENTIAL");
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
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

    private static LocalNativeSandboxException failure(String code, String message) {
        return new LocalNativeSandboxException(code, message);
    }

    private record BoundedBytes(byte[] bytes, boolean truncated) {}

    private enum WaitOutcome {
        FINISHED,
        OUTPUT_LIMIT_EXCEEDED,
        TIMED_OUT,
        CANCELLED,
        PROCESS_LIMIT_EXCEEDED
    }
}
