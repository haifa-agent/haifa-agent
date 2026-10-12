package io.haifa.agent.sandbox.host;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class HostGuardedSandboxProvider implements SandboxProvider {
    public static final String PROVIDER_ID = "host-guarded";
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AZURE_CLIENT_SECRET",
            "GOOGLE_APPLICATION_CREDENTIALS");
    private static final long OUTPUT_DRAIN_TIMEOUT_MILLIS = 5000;
    private static final long OUTPUT_DRAIN_GRACE_MILLIS = 2000;

    /**
     * Dedicated daemon threads keep the pipe readers off the shared common pool. A reader may stay blocked on a
     * surviving descendant that inherited the pipe write end, so it must never occupy shared worker threads.
     */
    private static final java.util.concurrent.atomic.AtomicLong OUTPUT_READER_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private static final Executor OUTPUT_READERS = command -> {
        Thread thread = new Thread(command, "haifa-host-output-reader-" + OUTPUT_READER_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    };

    private final WorkspaceStore workspaces;
    private final HostWorkspaceLocationStore locations;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final HostShell shell;
    private final Path scratchRoot;
    private final ScratchDirectoryDeleter scratchDirectoryDeleter;

    @FunctionalInterface
    interface ScratchDirectoryDeleter {
        void delete(Path target) throws IOException;
    }

    public HostGuardedSandboxProvider(
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this(
                workspaces,
                locations,
                identifiers,
                time,
                HostShell.auto(),
                Path.of(System.getProperty("java.io.tmpdir"), "haifa-agent-host-scratch"));
    }

    public HostGuardedSandboxProvider(
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            HostShell shell) {
        this(
                workspaces,
                locations,
                identifiers,
                time,
                shell,
                Path.of(System.getProperty("java.io.tmpdir"), "haifa-agent-host-scratch"));
    }

    public HostGuardedSandboxProvider(
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            HostShell shell,
            Path scratchRoot) {
        this(workspaces, locations, identifiers, time, shell, scratchRoot, HostGuardedSandboxProvider::deleteTree);
    }

    HostGuardedSandboxProvider(
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            IdentifierGenerator identifiers,
            TimeProvider time,
            HostShell shell,
            Path scratchRoot,
            ScratchDirectoryDeleter scratchDirectoryDeleter) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.shell = Objects.requireNonNull(shell, "shell must not be null");
        this.scratchRoot = requireScratchRoot(scratchRoot);
        this.scratchDirectoryDeleter =
                Objects.requireNonNull(scratchDirectoryDeleter, "scratchDirectoryDeleter must not be null");
    }

    public String shellDisplayName() {
        return shell.displayName();
    }

    private static void deleteTree(Path target) throws IOException {
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
        var fields = new java.util.ArrayList<String>();
        fields.add(providerId());
        fields.add(shell.displayName());
        fields.addAll(shell.invocationPrefix());
        fields.add(scratchRoot.toString());
        return SandboxConfigurationDigest.sha256Fields(fields);
    }

    @Override
    public boolean supportsManagedProcess() {
        return true;
    }

    @Override
    public SandboxSession open(SandboxProfile profile, io.haifa.agent.project.workspace.WorkspaceId workspaceId) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        try {
            preflight(profile);
        } catch (io.haifa.agent.sandbox.api.SandboxException exception) {
            throw failure(exception.code(), exception.getMessage());
        }
        var workspace =
                workspaces.find(workspaceId).orElseThrow(() -> failure("WORKSPACE_NOT_FOUND", "workspace not found"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) throw failure("WORKSPACE_INACTIVE", "workspace is inactive");
        try {
            Path root = locations.resolveForTrustedProvider(workspaceId);
            return new Session(new SandboxSessionId(identifiers.nextValue()), profile, workspace.id(), root);
        } catch (RuntimeException exception) {
            throw failure("ROOT_CHANGED", "workspace root identity changed or is unavailable");
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
            return execute(execution, ExecutionOutputObserver.noop());
        }

        @Override
        public synchronized SandboxProcessResult execute(SandboxExecution execution, ExecutionOutputObserver observer) {
            Objects.requireNonNull(observer, "observer must not be null");
            if (closed) throw failure("SESSION_CLOSED", "sandbox session is closed");
            if (!execution.workingDirectory().workspaceId().equals(workspaceId)) {
                throw failure("WORKSPACE_MISMATCH", "working directory belongs to another workspace");
            }
            validateCommand(execution);
            Path cwd = resolveDirectory(execution.workingDirectory());
            Path scratch =
                    execution.scratchSpace().isPresent() ? createScratchDirectory(execution.scratchSpace()) : null;
            Map<String, String> environment;
            try {
                environment = validateEnvironment(execution.environment(), execution.scratchSpace(), scratch);
            } catch (RuntimeException exception) {
                cleanupScratchDirectory(scratch);
                throw exception;
            }
            Instant started = time.now();
            SandboxProcessResult result;
            try {
                ProcessBuilder builder = new ProcessBuilder(launchCommand(execution));
                builder.directory(cwd.toFile());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                builder.environment().clear();
                builder.environment().putAll(environment);
                Process process = builder.start();
                current = process;
                observer.onStarted(new io.haifa.agent.execution.api.ExecutionProcessIdentity(process.pid()));
                try (var standardInput = process.getOutputStream()) {
                    standardInput.write(execution.input().bytes());
                }
                var outputLimitExceeded = new java.util.concurrent.atomic.AtomicBoolean();
                Set<ProcessHandle> observedDescendants = ConcurrentHashMap.newKeySet();
                var stdout = CompletableFuture.supplyAsync(
                        () -> read(
                                process.getInputStream(),
                                execution.limits().maxStdoutBytes(),
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                                observer,
                                execution.limits().outputOverflowPolicy(),
                                outputLimitExceeded),
                        OUTPUT_READERS);
                var stderr = CompletableFuture.supplyAsync(
                        () -> read(
                                process.getErrorStream(),
                                execution.limits().maxStderrBytes(),
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDERR,
                                observer,
                                execution.limits().outputOverflowPolicy(),
                                outputLimitExceeded),
                        OUTPUT_READERS);
                WaitOutcome outcome = waitFor(
                        process,
                        execution.limits().timeout(),
                        execution.limits().maxProcesses(),
                        outputLimitExceeded,
                        observedDescendants);
                SandboxProcessStatus status;
                boolean treeTerminated = true;
                Integer exitCode = null;
                if (outcome == WaitOutcome.CANCELLED) {
                    treeTerminated = terminateTree(process);
                } else if (outcome == WaitOutcome.FINISHED) {
                    exitCode = process.exitValue();
                } else {
                    treeTerminated = terminateTree(process);
                }
                status = settledStatus(outcome, treeTerminated);
                awaitOutputDrain(CompletableFuture.allOf(stdout, stderr), process, observedDescendants);
                BoundedBytes out = stdout.getNow(BoundedBytes.incomplete());
                BoundedBytes err = stderr.getNow(BoundedBytes.incomplete());
                if (outputLimitExceeded.get()
                        && execution.limits().outputOverflowPolicy()
                                == io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE
                        && status == SandboxProcessStatus.EXITED) {
                    status = SandboxProcessStatus.OUTPUT_LIMIT_EXCEEDED;
                    exitCode = null;
                }
                Instant ended = time.now();
                result = new SandboxProcessResult(
                        status,
                        exitCode,
                        out.bytes(),
                        err.bytes(),
                        started,
                        ended,
                        out.truncated(),
                        err.truncated(),
                        treeTerminated,
                        safeObservedProcesses(process),
                        scratch != null,
                        false,
                        settlementFailureCode(outcome, treeTerminated),
                        !out.complete() || !err.complete());
            } catch (HostSandboxException exception) {
                cleanupScratchDirectory(scratch);
                throw exception;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                boolean treeTerminated = current == null || terminateTree(current);
                result = new SandboxProcessResult(
                        SandboxProcessStatus.UNKNOWN,
                        null,
                        new byte[0],
                        new byte[0],
                        started,
                        time.now(),
                        false,
                        false,
                        treeTerminated,
                        current == null ? 0 : safeObservedProcesses(current),
                        scratch != null,
                        false,
                        null,
                        true);
            } finally {
                current = null;
                cancelRequested = false;
            }
            boolean cleaned = cleanupScratchDirectory(scratch);
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
                        scratch != null,
                        true,
                        result.failureCode(),
                        result.outputIncomplete());
            }
            return result;
        }

        @Override
        public synchronized io.haifa.agent.sandbox.api.SandboxManagedProcess openManagedProcess(
                SandboxExecution execution) {
            if (closed) throw failure("SESSION_CLOSED", "sandbox session is closed");
            if (current != null && current.isAlive()) {
                throw failure("SESSION_BUSY", "sandbox session already has a live process");
            }
            if (!execution.workingDirectory().workspaceId().equals(workspaceId)) {
                throw failure("WORKSPACE_MISMATCH", "working directory belongs to another workspace");
            }
            if (execution.command().mode() != ExecutionCommandMode.DIRECT) {
                throw failure("MANAGED_SHELL_DENIED", "managed process sessions require a direct command");
            }
            validateCommand(execution);
            Path cwd = resolveDirectory(execution.workingDirectory());
            Path scratch =
                    execution.scratchSpace().isPresent() ? createScratchDirectory(execution.scratchSpace()) : null;
            Map<String, String> environment;
            try {
                environment = validateEnvironment(execution.environment(), execution.scratchSpace(), scratch);
            } catch (RuntimeException exception) {
                cleanupScratchDirectory(scratch);
                throw exception;
            }
            try {
                ProcessBuilder builder = new ProcessBuilder(launchCommand(execution));
                builder.directory(cwd.toFile());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                builder.environment().clear();
                builder.environment().putAll(environment);
                Process process = builder.start();
                current = process;
                return new Managed(process, execution, time.now(), scratch);
            } catch (IOException exception) {
                cleanupScratchDirectory(scratch);
                throw failure("PROCESS_START_FAILED", "managed process could not be started");
            }
        }

        @Override
        public boolean cancel() {
            cancelRequested = true;
            Process process = current;
            return process == null || terminateTree(process);
        }

        private WaitOutcome waitFor(
                Process process,
                Duration timeout,
                Optional<Integer> maxProcesses,
                java.util.concurrent.atomic.AtomicBoolean outputLimitExceeded,
                Set<ProcessHandle> observedDescendants)
                throws InterruptedException {
            long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
            while (process.isAlive()) {
                if (cancelRequested) return WaitOutcome.CANCELLED;
                if (outputLimitExceeded.get()) return WaitOutcome.OUTPUT_LIMIT_EXCEEDED;
                List<ProcessHandle> descendants =
                        process.toHandle().descendants().toList();
                observedDescendants.addAll(descendants);
                if (maxProcesses.isPresent() && 1 + descendants.size() > maxProcesses.get()) {
                    return WaitOutcome.PROCESS_LIMIT_EXCEEDED;
                }
                long remainingMillis = deadlineMillis - System.currentTimeMillis();
                if (remainingMillis <= 0) return WaitOutcome.TIMED_OUT;
                long waitMillis = Math.max(1, Math.min(20, remainingMillis));
                process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            }
            return cancelRequested ? WaitOutcome.CANCELLED : WaitOutcome.FINISHED;
        }

        @Override
        public void close() {
            cancel();
            closed = true;
        }

        private void validateCommand(SandboxExecution execution) {
            if (execution.command().mode() == ExecutionCommandMode.SHELL) {
                if (!profile.shellAllowed()) throw failure("SHELL_DENIED", "shell execution is denied by profile");
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

        private List<String> launchCommand(SandboxExecution execution) {
            return execution.command().mode() == ExecutionCommandMode.SHELL
                    ? shell.launch(execution.command().shellCommand())
                    : execution.command().argv();
        }

        private final class Managed implements io.haifa.agent.sandbox.api.SandboxManagedProcess {
            private final Process process;
            private final SandboxExecution execution;
            private final Instant startedAt;
            private final Path scratch;
            private final java.util.concurrent.LinkedBlockingQueue<io.haifa.agent.execution.api.ProcessOutputChunk>
                    output = new java.util.concurrent.LinkedBlockingQueue<>(1024);
            private final java.util.concurrent.atomic.AtomicInteger stdoutBytes =
                    new java.util.concurrent.atomic.AtomicInteger();
            private final java.util.concurrent.atomic.AtomicInteger stderrBytes =
                    new java.util.concurrent.atomic.AtomicInteger();
            private final java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit;
            private final java.util.concurrent.atomic.AtomicBoolean managedClosed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.atomic.AtomicBoolean timedOut =
                    new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.atomic.AtomicBoolean scratchCleanupFailed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            private final Object scratchCleanupLock = new Object();
            private boolean scratchCleanupAttempted;
            private boolean scratchCleanupSuccessful;

            private Managed(Process process, SandboxExecution execution, Instant startedAt, Path scratch) {
                this.process = process;
                this.execution = execution;
                this.startedAt = startedAt;
                this.scratch = scratch;
                Thread.ofVirtual()
                        .name("haifa-managed-stdout")
                        .start(() -> pump(
                                process.getInputStream(),
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                                execution.limits().maxStdoutBytes(),
                                stdoutBytes));
                Thread.ofVirtual()
                        .name("haifa-managed-stderr")
                        .start(() -> pump(
                                process.getErrorStream(),
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDERR,
                                execution.limits().maxStderrBytes(),
                                stderrBytes));
                this.exit = process.onExit().thenApply(ignored -> {
                    var status = timedOut.get()
                            ? io.haifa.agent.execution.api.ExecutionStatus.TIMED_OUT
                            : cancelRequested
                                    ? io.haifa.agent.execution.api.ExecutionStatus.CANCELLED
                                    : io.haifa.agent.execution.api.ExecutionStatus.EXITED;
                    cancelRequested = false;
                    current = null;
                    boolean cleaned = cleanupScratchOnce();
                    if (!cleaned) {
                        status = io.haifa.agent.execution.api.ExecutionStatus.UNKNOWN;
                    }
                    return new io.haifa.agent.execution.api.ProcessExit(status, process.exitValue(), true, time.now());
                });
                java.util.concurrent.CompletableFuture.delayedExecutor(
                                execution.limits().timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            if (process.isAlive()) {
                                timedOut.set(true);
                                cancel();
                            }
                        });
            }

            @Override
            public Instant startedAt() {
                return startedAt;
            }

            @Override
            public void write(io.haifa.agent.execution.api.ProcessInputChunk input) {
                if (managedClosed.get() || !process.isAlive()) {
                    throw failure("PROCESS_CLOSED", "managed process is closed");
                }
                try {
                    synchronized (process.getOutputStream()) {
                        process.getOutputStream().write(input.bytes());
                        process.getOutputStream().flush();
                    }
                } catch (IOException exception) {
                    throw failure("PROCESS_STDIN_FAILED", "managed process stdin write failed");
                }
            }

            @Override
            public java.util.Optional<io.haifa.agent.execution.api.ProcessOutputChunk> read(Duration timeout) {
                try {
                    return java.util.Optional.ofNullable(
                            output.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return java.util.Optional.empty();
                }
            }

            @Override
            public java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit() {
                return exit;
            }

            @Override
            public int observedProcessCount() {
                return HostGuardedSandboxProvider.observedProcesses(process);
            }

            @Override
            public boolean scratchProvisioned() {
                return scratch != null;
            }

            @Override
            public boolean scratchCleanupFailed() {
                return scratchCleanupFailed.get();
            }

            @Override
            public boolean cancel() {
                cancelRequested = true;
                return terminateTree(process);
            }

            @Override
            public void close() {
                if (managedClosed.compareAndSet(false, true)) {
                    if (process.isAlive()) cancel();
                    if (!process.isAlive()) cleanupScratchOnce();
                }
            }

            private boolean cleanupScratchOnce() {
                synchronized (scratchCleanupLock) {
                    if (!scratchCleanupAttempted) {
                        scratchCleanupSuccessful = cleanupScratchDirectory(scratch);
                        scratchCleanupAttempted = true;
                        scratchCleanupFailed.set(!scratchCleanupSuccessful);
                    }
                    return scratchCleanupSuccessful;
                }
            }

            private void pump(
                    InputStream input,
                    io.haifa.agent.execution.api.ExecutionOutputChannel channel,
                    int limit,
                    java.util.concurrent.atomic.AtomicInteger consumed) {
                boolean truncated = false;
                try (input) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (execution.limits().maxProcesses().isPresent()
                                && observedProcessCount()
                                        > execution.limits().maxProcesses().get()) {
                            truncated = true;
                            cancel();
                            break;
                        }
                        int remaining = limit - consumed.get();
                        int accepted = Math.max(0, Math.min(remaining, count));
                        if (accepted > 0) {
                            consumed.addAndGet(accepted);
                            if (!output.offer(new io.haifa.agent.execution.api.ProcessOutputChunk(
                                    channel, java.util.Arrays.copyOf(buffer, accepted), false, false))) {
                                truncated = true;
                                cancel();
                                break;
                            }
                        }
                        if (accepted < count) {
                            truncated = true;
                            cancel();
                            break;
                        }
                    }
                } catch (IOException exception) {
                    truncated = true;
                } finally {
                    output.offer(
                            new io.haifa.agent.execution.api.ProcessOutputChunk(channel, new byte[0], true, truncated));
                }
            }
        }

        private Map<String, String> validateEnvironment(
                Map<String, String> requested, ExecutionScratchSpaceSpec scratchSpace, Path scratch) {
            var safe = new java.util.HashMap<String, String>();
            requested.forEach((name, value) -> {
                String upper = name.toUpperCase(Locale.ROOT);
                if (!profile.allowedEnvironmentNames().contains(name)
                        || FORBIDDEN_ENVIRONMENT.contains(upper)
                        || looksLikeSecretName(upper)) {
                    throw failure("ENVIRONMENT_DENIED", "environment lease contains a denied name");
                }
                safe.put(name, value);
            });
            if (scratch != null) {
                scratchSpace.rootEnvironmentNames().forEach(name -> safe.put(name, scratch.toString()));
                scratchSpace
                        .childBindings()
                        .forEach(binding -> safe.put(
                                binding.environmentName(),
                                scratch.resolve(binding.relativeDirectory()).toString()));
            }
            return Map.copyOf(safe);
        }

        private Path createScratchDirectory(ExecutionScratchSpaceSpec scratchSpace) {
            Path directory = null;
            try {
                Files.createDirectories(scratchRoot);
                SecureFilePermissions.secureDirectory(scratchRoot);
                if (isLink(scratchRoot) || overlaps(root, scratchRoot)) {
                    throw new IOException("scratch root identity is unsafe");
                }
                directory = Files.createTempDirectory(scratchRoot, "session-");
                SecureFilePermissions.secureDirectory(directory);
                for (var binding : scratchSpace.childBindings()) {
                    Path current = directory;
                    for (String segment : binding.relativeDirectory().split("/")) {
                        current = current.resolve(segment);
                        if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                            Files.createDirectory(current);
                        }
                        SecureFilePermissions.secureDirectory(current);
                    }
                    if (!current.normalize().startsWith(directory) || isLink(current) || !Files.isWritable(current)) {
                        throw new IOException("scratch child is unsafe");
                    }
                }
                if (!Files.isWritable(directory)) throw new IOException("scratch root is not writable");
                return directory;
            } catch (IOException exception) {
                cleanupScratchDirectory(directory);
                throw failure("SCRATCH_PROVISION_FAILED", "host scratch space could not be provisioned", exception);
            }
        }

        private boolean cleanupScratchDirectory(Path directory) {
            if (directory == null) return true;
            Path target = directory.toAbsolutePath().normalize();
            Path canonicalTarget = canonicalizeForComparison(target);
            Path canonicalScratchRoot = canonicalizeForComparison(scratchRoot);
            if (!canonicalTarget.startsWith(canonicalScratchRoot) || canonicalTarget.equals(canonicalScratchRoot)) {
                return false;
            }
            try {
                scratchDirectoryDeleter.delete(target);
                return Files.notExists(target, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                return false;
            }
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

    /**
     * Waits once for both output readers together, so the worst-case drain is one timeout plus one grace rather
     * than one per channel. When the drain does not complete it releases surviving pipe holders and tries once more,
     * but never closes the streams from this thread: Windows blocks FileInputStream.close() while a concurrent read
     * is parked on the same descriptor, which is the deadlock this path must avoid.
     */
    private static void awaitOutputDrain(
            CompletableFuture<Void> drained, Process process, Set<ProcessHandle> observedDescendants) {
        try {
            drained.get(OUTPUT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception exception) {
            // A pipe that never drains is held by a surviving descendant that inherited the write end.
        }
        releaseSurvivingDescendants(process, observedDescendants);
        try {
            drained.get(OUTPUT_DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            // The reader stays blocked on a pipe that could not be released; incomplete output is reported instead.
        }
    }

    /**
     * Forces surviving descendants to release output pipes inherited from a command whose own process already
     * exited. Only invoked when output collection did not drain, so ordinary commands that exit cleanly are never
     * affected. The immediate process is targeted as well in case a timeout left it alive.
     */
    private static void releaseSurvivingDescendants(Process process, Set<ProcessHandle> observedDescendants) {
        try {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // One unreleasable process must not stop the individual cleanup below.
        }
        for (ProcessHandle descendant : observedDescendants) {
            try {
                if (descendant.isAlive()) {
                    // destroyForcibly does not cascade to grandchildren on Windows, so kill the subtree first.
                    descendant.descendants().forEach(ProcessHandle::destroyForcibly);
                    descendant.destroyForcibly();
                }
            } catch (RuntimeException ignored) {
                // A single unreleasable process must not stop the remaining cleanup.
            }
        }
    }

    static SandboxProcessStatus settledStatus(WaitOutcome outcome, boolean treeTerminated) {
        return switch (outcome) {
            case FINISHED -> SandboxProcessStatus.EXITED;
            case CANCELLED -> treeTerminated ? SandboxProcessStatus.CANCELLED : SandboxProcessStatus.UNKNOWN;
            case OUTPUT_LIMIT_EXCEEDED ->
                treeTerminated ? SandboxProcessStatus.OUTPUT_LIMIT_EXCEEDED : SandboxProcessStatus.UNKNOWN;
            case PROCESS_LIMIT_EXCEEDED ->
                treeTerminated ? SandboxProcessStatus.PROCESS_LIMIT_EXCEEDED : SandboxProcessStatus.UNKNOWN;
            case TIMED_OUT -> treeTerminated ? SandboxProcessStatus.TIMED_OUT : SandboxProcessStatus.UNKNOWN;
        };
    }

    static String settlementFailureCode(WaitOutcome outcome, boolean treeTerminated) {
        return outcome == WaitOutcome.TIMED_OUT && !treeTerminated ? "TIMEOUT_TREE_UNCONFIRMED" : null;
    }

    static BoundedBytes read(
            InputStream input,
            int maximum,
            io.haifa.agent.execution.api.ExecutionOutputChannel channel,
            ExecutionOutputObserver observer,
            io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy overflowPolicy,
            java.util.concurrent.atomic.AtomicBoolean outputLimitExceeded) {
        var output = new io.haifa.agent.execution.api.BoundedOutputBuffer(maximum);
        try (input) {
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
            return new BoundedBytes(output.bytes(), output.truncated(), true);
        } catch (IOException exception) {
            notifyObserver(
                    observer, new io.haifa.agent.execution.api.ProcessOutputChunk(channel, new byte[0], true, true));
            return new BoundedBytes(output.bytes(), true, false);
        }
    }

    private static void notifyObserver(
            ExecutionOutputObserver observer, io.haifa.agent.execution.api.ProcessOutputChunk chunk) {
        try {
            observer.onOutput(chunk);
        } catch (RuntimeException ignored) {
            // Output presentation must not bypass process cleanup or execution audit.
        }
    }

    private static boolean terminateTree(Process process) {
        List<ProcessHandle> descendants =
                new ArrayList<>(process.toHandle().descendants().toList());
        if (isWindowsHost()) terminateWindowsTree(process.pid());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
            descendants.stream()
                    .filter(HostGuardedSandboxProvider::isProcessAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (isProcessAlive(process.toHandle())) process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((isProcessAlive(process.toHandle())
                            || descendants.stream().anyMatch(HostGuardedSandboxProvider::isProcessAlive))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !isProcessAlive(process.toHandle())
                && descendants.stream().noneMatch(HostGuardedSandboxProvider::isProcessAlive);
    }

    private static boolean isProcessAlive(ProcessHandle handle) {
        return ProcessHandle.of(handle.pid()).filter(ProcessHandle::isAlive).isPresent();
    }

    private static void terminateWindowsTree(long processId) {
        String systemRoot = System.getenv("SystemRoot");
        String executable = systemRoot == null || systemRoot.isBlank()
                ? "taskkill"
                : Path.of(systemRoot, "System32", "taskkill.exe").toString();
        try {
            Process taskkill = new ProcessBuilder(executable, "/PID", Long.toString(processId), "/T", "/F")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            taskkill.waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }

    private static int observedProcesses(Process process) {
        return 1 + Math.toIntExact(process.toHandle().descendants().limit(63).count());
    }

    private static int safeObservedProcesses(Process process) {
        try {
            return observedProcesses(process);
        } catch (RuntimeException ignored) {
            return 1;
        }
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

    private static Path requireScratchRoot(Path value) {
        Path root = Objects.requireNonNull(value, "scratchRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (root.getParent() == null || canonicalizeForComparison(root).equals(canonicalizeForComparison(home))) {
            throw new IllegalArgumentException("scratchRoot must be private and outside the user home");
        }
        return root;
    }

    private static boolean overlaps(Path first, Path second) {
        Path left = canonicalizeForComparison(first);
        Path right = canonicalizeForComparison(second);
        return left.startsWith(right) || right.startsWith(left);
    }

    private static Path canonicalizeForComparison(Path value) {
        Path absolute = value.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) return absolute;
        try {
            Path canonicalBase = existing.toRealPath(LinkOption.NOFOLLOW_LINKS);
            return canonicalBase.resolve(existing.relativize(absolute)).normalize();
        } catch (IOException exception) {
            return absolute;
        }
    }

    private static HostSandboxException failure(String code, String message) {
        return new HostSandboxException(code, message);
    }

    private static HostSandboxException failure(String code, String message, Throwable cause) {
        return new HostSandboxException(code, message, cause);
    }

    record BoundedBytes(byte[] bytes, boolean truncated, boolean complete) {
        private static BoundedBytes incomplete() {
            return new BoundedBytes(new byte[0], true, false);
        }
    }

    enum WaitOutcome {
        FINISHED,
        OUTPUT_LIMIT_EXCEEDED,
        TIMED_OUT,
        CANCELLED,
        PROCESS_LIMIT_EXCEEDED
    }
}
