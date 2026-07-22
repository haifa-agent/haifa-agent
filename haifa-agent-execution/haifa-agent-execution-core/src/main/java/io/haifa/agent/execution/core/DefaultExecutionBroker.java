package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.EnvironmentLeaseResolver;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputStore;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ExecutionStore;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifest;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProviderResolver;
import io.haifa.agent.sandbox.api.SandboxResolver;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
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
    private final WorkspaceManifestService manifests;
    private final ManifestDiffService manifestDiff;
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
            WorkspaceManifestService manifests,
            ManifestDiffService manifestDiff,
            ObservedFileChangeService observedChanges) {
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
        this.outputs = Objects.requireNonNull(outputs, "outputs must not be null");
        this.environments = Objects.requireNonNull(environments, "environments must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.manifests = Objects.requireNonNull(manifests, "manifests must not be null");
        this.manifestDiff = Objects.requireNonNull(manifestDiff, "manifestDiff must not be null");
        this.observedChanges = Objects.requireNonNull(observedChanges, "observedChanges must not be null");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Optional<ExecutionResult> replay = executions.findByIdempotencyKey(request.idempotencyKey());
        if (replay.isPresent()) {
            var previous = executions.findRequest(replay.orElseThrow().id()).orElseThrow();
            if (!sameIntent(previous, request))
                throw reject("IDEMPOTENCY_CONFLICT", "idempotency key has different intent");
            authorize(request);
            return replay.orElseThrow().asReplay();
        }
        authorize(request);
        policy.authorize(request);
        var profile = profiles.resolve(request.sandboxProfileRef());
        var provider = providers.resolve(profile);
        Map<String, String> environment = Map.copyOf(environments.resolve(request.environmentRef()));
        WorkspaceManifest before = manifests.capture(request.workspaceId());
        executions.create(request);
        SandboxSession session = provider.open(profile, new WorkspaceMount(request.workspaceId(), false));
        active.put(request.id(), session);
        try (session) {
            var process = session.execute(
                    new SandboxExecution(request.command(), request.workingDirectory(), environment, request.limits()));
            byte[] stdoutBytes = redact(process.stdout(), environment);
            byte[] stderrBytes = redact(process.stderr(), environment);
            var stdout = outputs.store(
                    request.id(), ExecutionOutputChannel.STDOUT, stdoutBytes, 4096, process.stdoutTruncated());
            var stderr = outputs.store(
                    request.id(), ExecutionOutputChannel.STDERR, stderrBytes, 4096, process.stderrTruncated());
            io.haifa.agent.project.changeset.FileChangeSetId changeSetId = null;
            ExecutionStatus status = map(process.status(), process.exitCode());
            ExecutionFailure failure = failure(status, process.processTreeTerminated());
            try {
                WorkspaceManifest after = manifests.capture(request.workspaceId());
                var changes = manifestDiff.diff(before, after);
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
            } catch (RuntimeException manifestFailure) {
                status = ExecutionStatus.UNKNOWN;
                failure = new ExecutionFailure(
                        "MANIFEST_UNCERTAIN", "post-execution file changes could not be fully audited");
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
                    false);
            executions.complete(request, result);
            return result;
        } finally {
            active.remove(request.id());
        }
    }

    @Override
    public io.haifa.agent.execution.api.ManagedProcessSession openManagedSession(
            io.haifa.agent.execution.api.ManagedProcessRequest managedRequest) {
        Objects.requireNonNull(managedRequest, "managedRequest must not be null");
        ExecutionRequest request = managedRequest.execution();
        if (executions.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            throw reject("MANAGED_SESSION_REPLAY_DENIED", "managed process sessions cannot be replayed");
        }
        authorize(request);
        policy.authorize(request);
        var profile = profiles.resolve(request.sandboxProfileRef());
        var provider = providers.resolve(profile);
        Map<String, String> environment = Map.copyOf(environments.resolve(request.environmentRef()));
        WorkspaceManifest before = manifests.capture(request.workspaceId());
        executions.create(request);
        SandboxSession sandbox = provider.open(profile, new WorkspaceMount(request.workspaceId(), false));
        active.put(request.id(), sandbox);
        try {
            var process = sandbox.openManagedProcess(
                    new SandboxExecution(request.command(), request.workingDirectory(), environment, request.limits()));
            return new BrokerManagedSession(request, sandbox, process, environment, before);
        } catch (RuntimeException exception) {
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

    private final class BrokerManagedSession implements io.haifa.agent.execution.api.ManagedProcessSession {
        private final ExecutionRequest request;
        private final SandboxSession sandbox;
        private final io.haifa.agent.sandbox.api.SandboxManagedProcess process;
        private final Map<String, String> environment;
        private final WorkspaceManifest before;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit;

        private BrokerManagedSession(
                ExecutionRequest request,
                SandboxSession sandbox,
                io.haifa.agent.sandbox.api.SandboxManagedProcess process,
                Map<String, String> environment,
                WorkspaceManifest before) {
            this.request = request;
            this.sandbox = sandbox;
            this.process = process;
            this.environment = environment;
            this.before = before;
            this.exit = process.exit().thenApply(this::complete);
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
            return process.read(timeout).map(chunk -> {
                byte[] redacted = redact(chunk.bytes(), environment);
                synchronized (this) {
                    ByteArrayOutputStream target = chunk.channel() == ExecutionOutputChannel.STDOUT ? stdout : stderr;
                    target.writeBytes(redacted);
                }
                return new io.haifa.agent.execution.api.ProcessOutputChunk(
                        chunk.channel(), redacted, chunk.endOfStream(), chunk.truncated());
            });
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
            try {
                process.close();
            } finally {
                sandbox.close();
                active.remove(request.id());
            }
        }

        private io.haifa.agent.execution.api.ProcessExit complete(
                io.haifa.agent.execution.api.ProcessExit processExit) {
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
                WorkspaceManifest after = manifests.capture(request.workspaceId());
                var changes = manifestDiff.diff(before, after);
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
            } catch (RuntimeException manifestFailure) {
                status = ExecutionStatus.UNKNOWN;
                executionFailure = new ExecutionFailure(
                        "MANIFEST_UNCERTAIN", "post-execution file changes could not be fully audited");
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
                    false);
            executions.complete(request, result);
            active.remove(request.id());
            return new io.haifa.agent.execution.api.ProcessExit(
                    status, processExit.exitCode(), processExit.processTreeTerminated(), processExit.endedAt());
        }
    }

    private static boolean sameIntent(ExecutionRequest first, ExecutionRequest second) {
        return first.workspaceId().equals(second.workspaceId())
                && first.workingDirectory().equals(second.workingDirectory())
                && first.command().equals(second.command())
                && first.sandboxProfileRef().equals(second.sandboxProfileRef());
    }

    private static ExecutionStatus map(SandboxProcessStatus status, Integer exitCode) {
        return switch (status) {
            case EXITED -> exitCode != null && exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
            case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
            case CANCELLED -> ExecutionStatus.CANCELLED;
            case UNKNOWN -> ExecutionStatus.UNKNOWN;
        };
    }

    private static ExecutionFailure failure(ExecutionStatus status, boolean treeTerminated) {
        return switch (status) {
            case SUCCEEDED -> null;
            case FAILED -> new ExecutionFailure("NON_ZERO_EXIT", "process exited with a non-zero status");
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

    private static byte[] redact(byte[] source, Map<String, String> environment) {
        byte[] redacted = source.clone();
        for (String secret : environment.values()) {
            if (secret != null && !secret.isEmpty()) {
                redacted = replace(
                        redacted, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[] {'*', '*', '*'});
            }
        }
        return redacted;
    }

    private static byte[] replace(byte[] source, byte[] target, byte[] replacement) {
        if (target.length == 0 || source.length < target.length) return source;
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        int index = 0;
        while (index <= source.length - target.length) {
            boolean match = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                output.writeBytes(replacement);
                index += target.length;
            } else {
                output.write(source[index++]);
            }
        }
        output.write(source, index, source.length - index);
        return output.toByteArray();
    }

    private static ExecutionRejectedException reject(String code, String message) {
        return new ExecutionRejectedException(code, message);
    }
}
