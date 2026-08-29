package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.EnvironmentLeaseResolver;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionOutputStore;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ExecutionStore;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.core.change.WorkspaceChangeObservation;
import io.haifa.agent.execution.core.change.WorkspaceChangeObserver;
import io.haifa.agent.execution.core.change.WorkspaceChangeObserverException;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxPreflight;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxProviderResolver;
import io.haifa.agent.sandbox.api.SandboxResolver;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultExecutionBroker implements ExecutionBroker {
    private final ExecutionStore executions;
    private final ExecutionOutputStore outputs;
    private final EnvironmentLeaseResolver environments;
    private final ExecutionPolicy policy;
    private final SandboxResolver profiles;
    private final SandboxProviderResolver providers;
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceChangeObserver workspaceChanges;
    private final ObservedFileChangeService observedChanges;
    private final ConcurrentHashMap<ExecutionId, SandboxSession> active = new ConcurrentHashMap<>();

    public DefaultExecutionBroker(
            ExecutionStore executions,
            ExecutionOutputStore outputs,
            EnvironmentLeaseResolver environments,
            ExecutionPolicy policy,
            SandboxResolver profiles,
            SandboxProviderResolver providers,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            WorkspaceChangeObserver workspaceChanges,
            ObservedFileChangeService observedChanges) {
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
        this.outputs = Objects.requireNonNull(outputs, "outputs must not be null");
        this.environments = Objects.requireNonNull(environments, "environments must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.workspaceChanges = Objects.requireNonNull(workspaceChanges, "workspaceChanges must not be null");
        this.observedChanges = Objects.requireNonNull(observedChanges, "observedChanges must not be null");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        return execute(request, ExecutionOutputObserver.noop());
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Optional<ExecutionResult> replay = executions.findByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            var previous = executions.findRequest(replay.orElseThrow().id()).orElseThrow();
            if (!sameIntent(previous, request))
                throw reject("IDEMPOTENCY_CONFLICT", "idempotency key has different intent");
            authorize(request);
            policy.authorize(request);
            return replay.orElseThrow().asReplay();
        }
        authorize(request);
        policy.authorize(request);
        ResolvedSandbox resolved = resolveSandbox(request, false);
        ResolvedExecutionEnvironment environment = environments.resolve(request.environmentRef());
        List<byte[]> secrets = RedactingExecutionOutputObserver.extractSecrets(environment);
        WorkspaceChangeObservation changeObservation = beginChangeObservation(request);
        SandboxSession session;
        try {
            executions.create(request);
            session = resolved.provider().open(resolved.profile(), resolved.mount());
        } catch (RuntimeException exception) {
            changeObservation.cancel();
            throw exception;
        }
        active.put(request.id(), session);
        try (session) {
            io.haifa.agent.sandbox.api.SandboxProcessResult process;
            try (var asynchronous = new BoundedAsyncExecutionOutputObserver(observer)) {
                ExecutionOutputObserver safeObserver = new RedactingExecutionOutputObserver(asynchronous, secrets);
                process = session.execute(
                        new SandboxExecution(
                                request.command(),
                                request.workingDirectory(),
                                environment.values(),
                                request.limits(),
                                request.input(),
                                request.scratchSpace()),
                        safeObserver);
            }
            byte[] stdoutBytes = RedactingExecutionOutputObserver.redactAll(process.stdout(), secrets);
            byte[] stderrBytes = RedactingExecutionOutputObserver.redactAll(process.stderr(), secrets);
            var stdout = outputs.store(
                    request.id(), ExecutionOutputChannel.STDOUT, stdoutBytes, 4096, process.stdoutTruncated());
            var stderr = outputs.store(
                    request.id(), ExecutionOutputChannel.STDERR, stderrBytes, 4096, process.stderrTruncated());
            io.haifa.agent.project.changeset.FileChangeSetId changeSetId = null;
            ExecutionStatus status = map(process.status(), process.exitCode());
            ExecutionFailure failure = failure(status, process.processTreeTerminated());
            try {
                var changes = changeObservation.complete();
                if (!changes.isEmpty()) {
                    var workspace = workspaces.find(request.workspaceId()).orElseThrow();
                    changeSetId = observedChanges
                            .record(
                                    workspace,
                                    "execution:" + request.id().value(),
                                    request.context().runRef(),
                                    request.id().value(),
                                    request.context().actor(),
                                    request.context().policyDecisionRef(),
                                    changes)
                            .id();
                }
            } catch (RuntimeException observationFailure) {
                status = ExecutionStatus.UNKNOWN;
                failure = new ExecutionFailure(
                        WorkspaceChangeObserverException.RESYNC_FAILED,
                        "post-execution workspace changes could not be fully observed");
            }
            ExecutionResult result = new ExecutionResult(
                    request.id(),
                    status,
                    process.exitCode(),
                    process.startedAt(),
                    process.endedAt(),
                    stdout,
                    stderr,
                    changeSetId,
                    session.id().value(),
                    new ResourceUsageSummary(
                            Duration.between(process.startedAt(), process.endedAt()), process.observedProcessCount()),
                    failure,
                    false,
                    process.scratchProvisioned(),
                    process.scratchCleanupFailed());
            executions.complete(request, result);
            return result;
        } finally {
            changeObservation.cancel();
            active.remove(request.id());
        }
    }

    @Override
    public Optional<ExecutionResult> findByIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return executions.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public io.haifa.agent.execution.api.ManagedProcessSession openManagedSession(
            io.haifa.agent.execution.api.ManagedProcessRequest managedRequest) {
        Objects.requireNonNull(managedRequest, "managedRequest must not be null");
        ExecutionRequest request = managedRequest.execution();
        if (!request.input().isEmpty()) {
            throw new IllegalArgumentException("managed execution does not accept initial input");
        }
        if (executions.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            throw reject("MANAGED_SESSION_REPLAY_DENIED", "managed process sessions cannot be replayed");
        }
        authorize(request);
        policy.authorize(request);
        ResolvedSandbox resolved = resolveSandbox(request, true);
        ResolvedExecutionEnvironment environment = environments.resolve(request.environmentRef());
        List<byte[]> secrets = RedactingExecutionOutputObserver.extractSecrets(environment);
        WorkspaceChangeObservation changeObservation = beginChangeObservation(request);
        SandboxSession sandbox;
        try {
            executions.create(request);
            sandbox = resolved.provider().open(resolved.profile(), resolved.mount());
        } catch (RuntimeException exception) {
            changeObservation.cancel();
            throw exception;
        }
        active.put(request.id(), sandbox);
        try {
            var process = sandbox.openManagedProcess(new SandboxExecution(
                    request.command(),
                    request.workingDirectory(),
                    environment.values(),
                    request.limits(),
                    request.input(),
                    request.scratchSpace()));
            return new BrokerManagedSession(
                    request, sandbox, process, environment.values(), secrets, changeObservation);
        } catch (RuntimeException exception) {
            changeObservation.cancel();
            active.remove(request.id());
            sandbox.close();
            throw exception;
        }
    }

    @Override
    public boolean cancel(ExecutionId id) {
        SandboxSession session = active.get(id);
        return session != null && session.cancel();
    }

    @Override
    public Optional<ExecutionResult> find(ExecutionId id) {
        return executions.findResult(id);
    }

    private void authorize(ExecutionRequest request) {
        if (!request.context().allows("execution.run"))
            throw reject("CAPABILITY_DENIED", "execution capability is absent");
        var workspace = workspaces
                .find(request.workspaceId())
                .orElseThrow(() -> reject("WORKSPACE_NOT_FOUND", "workspace not found"));
        var binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> reject("BINDING_NOT_FOUND", "workspace binding not found"));
        if (!binding.permissions().allows(WorkspacePermission.EXECUTE)
                || !binding.capabilities().allows("execution.run")) {
            throw reject("WORKSPACE_EXECUTION_DENIED", "workspace execution capability is denied");
        }
    }

    private ResolvedSandbox resolveSandbox(ExecutionRequest request, boolean managedProcess) {
        SandboxProfile profile = profiles.resolve(request.sandboxProfileRef());
        if (!request.sandboxProfileRef().equals(profile.ref())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox profile reference does not match");
        }
        SandboxProvider provider = providers.resolve(profile);
        if (!profile.providerId().equals(provider.providerId())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox provider binding does not match");
        }
        SandboxPreflight preflight = provider.preflight(profile);
        if (!preflight.providerId().equals(profile.providerId())
                || !preflight.configurationDigest().equals(profile.providerConfigurationDigest())
                || !preflight.capabilities().satisfies(profile.requiredCapabilities())) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox preflight does not match the profile");
        }
        if (managedProcess && !preflight.managedProcessSupported()) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox provider does not support managed processes");
        }
        boolean readOnly = profile.filesystemPolicy().workspaceAccess() == SandboxWorkspaceAccess.READ_ONLY;
        return new ResolvedSandbox(profile, provider, new WorkspaceMount(request.workspaceId(), readOnly));
    }

    private final class BrokerManagedSession implements io.haifa.agent.execution.api.ManagedProcessSession {
        private final ExecutionRequest request;
        private final SandboxSession sandbox;
        private final io.haifa.agent.sandbox.api.SandboxManagedProcess process;
        private final Map<String, String> environment;
        private final List<byte[]> secrets;
        private final WorkspaceChangeObservation changeObservation;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit;
        private final RedactingExecutionOutputObserver redactingObserver;
        private final java.util.concurrent.ConcurrentLinkedQueue<io.haifa.agent.execution.api.ProcessOutputChunk>
                safeChunks = new java.util.concurrent.ConcurrentLinkedQueue<>();

        private BrokerManagedSession(
                ExecutionRequest request,
                SandboxSession sandbox,
                io.haifa.agent.sandbox.api.SandboxManagedProcess process,
                Map<String, String> environment,
                List<byte[]> secrets,
                WorkspaceChangeObservation changeObservation) {
            this.request = request;
            this.sandbox = sandbox;
            this.process = process;
            this.environment = environment;
            this.secrets = secrets;
            this.changeObservation = changeObservation;
            this.exit = process.exit().thenApply(this::complete);
            this.exit.whenComplete((ignored, failure) -> changeObservation.cancel());
            this.redactingObserver = new RedactingExecutionOutputObserver(
                    chunk -> {
                        synchronized (this) {
                            ByteArrayOutputStream target =
                                    chunk.channel() == ExecutionOutputChannel.STDOUT ? stdout : stderr;
                            target.writeBytes(chunk.bytes());
                        }
                        safeChunks.add(chunk);
                    },
                    secrets);
        }

        @Override
        public io.haifa.agent.execution.api.ManagedProcessSessionId id() {
            return new io.haifa.agent.execution.api.ManagedProcessSessionId(
                    request.id().value());
        }

        @Override
        public void write(io.haifa.agent.execution.api.ProcessInputChunk input) {
            if (closed.get()) throw new IllegalStateException("managed process session is closed");
            process.write(input);
        }

        @Override
        public Optional<io.haifa.agent.execution.api.ProcessOutputChunk> read(Duration timeout) {
            if (closed.get()) return Optional.empty();
            var buffered = safeChunks.poll();
            if (buffered != null) return Optional.of(buffered);
            var readChunk = process.read(timeout);
            if (readChunk.isPresent()) {
                redactingObserver.onOutput(readChunk.get());
                return Optional.ofNullable(safeChunks.poll());
            }
            if (process.exit().isDone()) {
                redactingObserver.flush();
                return Optional.ofNullable(safeChunks.poll());
            }
            return Optional.empty();
        }

        @Override
        public java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit() {
            return exit;
        }

        @Override
        public boolean cancel() {
            return process.cancel();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            redactingObserver.flush();
            try {
                process.close();
                exit.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                changeObservation.cancel();
                throw new IllegalStateException("managed execution close was interrupted", exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                changeObservation.cancel();
                throw new IllegalStateException("managed execution did not settle during close", exception);
            } finally {
                sandbox.close();
                active.remove(request.id());
            }
        }

        private io.haifa.agent.execution.api.ProcessExit complete(
                io.haifa.agent.execution.api.ProcessExit processExit) {
            redactingObserver.flush();
            byte[] stdoutBytes;
            byte[] stderrBytes;
            synchronized (this) {
                stdoutBytes = stdout.toByteArray();
                stderrBytes = stderr.toByteArray();
            }
            var storedStdout = outputs.store(request.id(), ExecutionOutputChannel.STDOUT, stdoutBytes, 4096, false);
            var storedStderr = outputs.store(request.id(), ExecutionOutputChannel.STDERR, stderrBytes, 4096, false);
            io.haifa.agent.project.changeset.FileChangeSetId changeSetId = null;
            ExecutionStatus status = processExit.status();
            ExecutionFailure executionFailure = failure(status, processExit.processTreeTerminated());
            try {
                var changes = changeObservation.complete();
                if (!changes.isEmpty()) {
                    var workspace = workspaces.find(request.workspaceId()).orElseThrow();
                    changeSetId = observedChanges
                            .record(
                                    workspace,
                                    "managed-execution:" + request.id().value(),
                                    request.context().runRef(),
                                    request.id().value(),
                                    request.context().actor(),
                                    request.context().policyDecisionRef(),
                                    changes)
                            .id();
                }
            } catch (RuntimeException observationFailure) {
                status = ExecutionStatus.UNKNOWN;
                executionFailure = new ExecutionFailure(
                        WorkspaceChangeObserverException.RESYNC_FAILED,
                        "post-execution workspace changes could not be fully observed");
            }
            ExecutionResult result = new ExecutionResult(
                    request.id(),
                    status,
                    processExit.exitCode(),
                    process.startedAt(),
                    processExit.endedAt(),
                    storedStdout,
                    storedStderr,
                    changeSetId,
                    sandbox.id().value(),
                    new ResourceUsageSummary(
                            Duration.between(process.startedAt(), processExit.endedAt()),
                            process.observedProcessCount()),
                    executionFailure,
                    false,
                    process.scratchProvisioned(),
                    process.scratchCleanupFailed());
            executions.complete(request, result);
            active.remove(request.id());
            return new io.haifa.agent.execution.api.ProcessExit(
                    status, processExit.exitCode(), processExit.processTreeTerminated(), processExit.endedAt());
        }
    }

    private static boolean sameIntent(ExecutionRequest first, ExecutionRequest second) {
        return first.context().equals(second.context())
                && first.workspaceId().equals(second.workspaceId())
                && first.workingDirectory().equals(second.workingDirectory())
                && first.command().equals(second.command())
                && first.environmentRef().equals(second.environmentRef())
                && first.limits().equals(second.limits())
                && first.sandboxProfileRef().equals(second.sandboxProfileRef())
                && first.input().equals(second.input())
                && first.scratchSpace().equals(second.scratchSpace())
                && first.invocationDigest().equals(second.invocationDigest());
    }

    private WorkspaceChangeObservation beginChangeObservation(ExecutionRequest request) {
        try {
            return workspaceChanges.begin(request.workspaceId());
        } catch (RuntimeException exception) {
            throw new ExecutionPreflightException(
                    WorkspaceChangeObserverException.UNAVAILABLE,
                    "workspace change observation could not be established before execution",
                    exception);
        }
    }

    private static ExecutionStatus map(SandboxProcessStatus status, Integer exitCode) {
        return switch (status) {
            case EXITED -> exitCode != null && exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
            case OUTPUT_LIMIT_EXCEEDED -> ExecutionStatus.OUTPUT_LIMIT_EXCEEDED;
            case PROCESS_LIMIT_EXCEEDED -> ExecutionStatus.PROCESS_LIMIT_EXCEEDED;
            case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
            case CANCELLED -> ExecutionStatus.CANCELLED;
            case UNKNOWN -> ExecutionStatus.UNKNOWN;
        };
    }

    private static ExecutionFailure failure(ExecutionStatus status, boolean treeTerminated) {
        return switch (status) {
            case SUCCEEDED -> null;
            case FAILED -> new ExecutionFailure("NON_ZERO_EXIT", "process exited with a non-zero status");
            case OUTPUT_LIMIT_EXCEEDED ->
                new ExecutionFailure(
                        treeTerminated ? "OUTPUT_LIMIT_EXCEEDED" : "OUTPUT_LIMIT_TREE_UNKNOWN",
                        treeTerminated
                                ? "process output exceeded its budget and the process tree was terminated"
                                : "process output exceeded its budget and process tree termination is uncertain");
            case PROCESS_LIMIT_EXCEEDED ->
                new ExecutionFailure(
                        "PROCESS_LIMIT_EXCEEDED",
                        "process count exceeded its budget and the process tree was terminated");
            case TIMED_OUT ->
                new ExecutionFailure(
                        treeTerminated ? "TIMEOUT" : "TIMEOUT_TREE_UNKNOWN",
                        treeTerminated
                                ? "process timed out and its tree was terminated"
                                : "process tree termination is uncertain");
            case CANCELLED -> new ExecutionFailure("CANCELLED", "execution was cancelled");
            case UNKNOWN -> new ExecutionFailure("OUTCOME_UNKNOWN", "execution outcome could not be determined");
        };
    }

    private static ExecutionRejectedException reject(String code, String message) {
        return new ExecutionRejectedException(code, message);
    }

    private record ResolvedSandbox(SandboxProfile profile, SandboxProvider provider, WorkspaceMount mount) {}
}
