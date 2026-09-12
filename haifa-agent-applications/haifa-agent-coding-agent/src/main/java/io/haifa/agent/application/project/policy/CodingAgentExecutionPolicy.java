package io.haifa.agent.application.project.policy;

import io.haifa.agent.application.project.tool.ProjectExecutionToolOperations;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.application.project.workspace.WorkspaceAccessStore;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.ExecutionPolicy;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Product-owned final execution policy for Coding Agent Runtime, CLI, and exact internal Git reads. */
public final class CodingAgentExecutionPolicy implements ExecutionPolicy {
    private static final int FULL_OUTPUT_BYTES_PER_CHANNEL = 16 * 1024 * 1024;
    private static final List<List<String>> INTERNAL_GIT_SUFFIXES = List.of(
            List.of("rev-parse", "--show-toplevel"),
            List.of("rev-parse", "--is-inside-work-tree"),
            List.of("rev-parse", "HEAD"),
            List.of("rev-parse", "--verify", "HEAD"),
            List.of("symbolic-ref", "--short", "-q", "HEAD"),
            List.of("submodule", "status"),
            List.of("status", "--porcelain=v1", "--untracked-files=normal"),
            List.of("diff", "--numstat", "HEAD", "--"));

    private final RuntimeToolExecutionVerifier runtime;
    private final WorkspaceAccessStore workspaceAccess;
    private final AuthorizedWorkspaceProvisioning provisioning;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final ExecutionEnvironmentRef environmentRef;
    private final SandboxProfileRef profileRef;
    private final ExecutionScratchSpaceSpec scratchSpace;
    private final Duration defaultTimeout;
    private final Duration maximumTimeout;
    private final int maximumModelOutputBytes;
    private final Optional<Integer> maximumProcesses;

    public CodingAgentExecutionPolicy(
            RuntimeToolExecutionVerifier runtime,
            WorkspaceAccessStore workspaceAccess,
            AuthorizedWorkspaceProvisioning provisioning,
            TenantRef tenant,
            PrincipalRef principal,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef profileRef,
            ExecutionScratchSpaceSpec scratchSpace,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            Optional<Integer> maximumProcesses) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
        this.environmentRef = Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        this.profileRef = Objects.requireNonNull(profileRef, "profileRef must not be null");
        this.scratchSpace = Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        this.maximumTimeout = Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        if (maximumModelOutputBytes < 1) {
            throw new IllegalArgumentException("execution policy limits must be positive");
        }
        Objects.requireNonNull(maximumProcesses, "maximumProcesses must not be null");
        if (maximumProcesses.isPresent()) {
            int limit = maximumProcesses.get();
            if (limit < 1 || limit > 64) {
                throw new IllegalArgumentException("maximumProcesses is out of range");
            }
        }
        this.maximumModelOutputBytes = maximumModelOutputBytes;
        this.maximumProcesses = maximumProcesses;
    }

    public CodingAgentExecutionPolicy(
            RuntimeToolExecutionVerifier runtime,
            WorkspaceAccessStore workspaceAccess,
            AuthorizedWorkspaceProvisioning provisioning,
            TenantRef tenant,
            PrincipalRef principal,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef profileRef,
            ExecutionScratchSpaceSpec scratchSpace,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumProcesses) {
        this(
                runtime,
                workspaceAccess,
                provisioning,
                tenant,
                principal,
                environmentRef,
                profileRef,
                scratchSpace,
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                Optional.of(maximumProcesses));
    }

    public CodingAgentExecutionPolicy(
            RuntimeToolExecutionVerifier runtime,
            WorkspaceAccessStore workspaceAccess,
            AuthorizedWorkspaceProvisioning provisioning,
            TenantRef tenant,
            PrincipalRef principal,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef profileRef,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumModelOutputBytes) {
        this(
                runtime,
                workspaceAccess,
                provisioning,
                tenant,
                principal,
                environmentRef,
                profileRef,
                ExecutionScratchSpaceSpec.none(),
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                Optional.empty());
    }

    @Override
    public void authorize(ExecutionRequest request, ExecutionPolicyEntryPoint entryPoint) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entryPoint, "entryPoint must not be null");
        if (entryPoint == ExecutionPolicyEntryPoint.MANAGED_SESSION) {
            throw denied("CODING_MANAGED_EXECUTION_DENIED", "Coding Agent has no managed execution product entry");
        }
        verifyOwner(request);
        switch (request.context().origin()) {
            case RUNTIME_TOOL -> authorizeRuntime(request);
            case PRODUCT_USER_COMMAND -> authorizeUserCommand(request);
            case PRODUCT_INTERNAL -> authorizeInternalGit(request);
        }
    }

    private void authorizeRuntime(ExecutionRequest request) {
        if (!request.context().allows("execution_run")) {
            throw denied("CODING_EXECUTION_CAPABILITY_DENIED", "Runtime execution capability is absent");
        }
        requireWorkspace(request, WorkspaceAccessMode.DEVELOP);
        var source = request.context()
                .sourceToolCallId()
                .orElseThrow(() -> denied("CODING_EXECUTION_SOURCE_DENIED", "Runtime execution source is absent"));
        try {
            runtime.verify(
                    request.context().tenant(),
                    request.context().runRef(),
                    request.context().actor(),
                    source,
                    (configuration, binding, toolRequest) ->
                            verifyRuntimeIntent(request, configuration, binding, toolRequest));
        } catch (SecurityException exception) {
            throw denied("CODING_EXECUTION_SOURCE_DENIED", exception.getMessage());
        }
    }

    private void authorizeUserCommand(ExecutionRequest request) {
        if (request.context().sourceToolCallId().isPresent()
                || !request.context().allows("execution_run")
                || request.command().mode() != ExecutionCommandMode.SHELL) {
            throw denied("CODING_USER_EXECUTION_DENIED", "CLI user execution origin is malformed");
        }
        requireWorkspace(request, WorkspaceAccessMode.DEVELOP);
        requireFixedCommon(request, environmentRef, profileRef, scratchSpace);
        if (SystemGitCliCommandClassifier.classify(request.command().shellCommand())
                        .risk()
                == SystemGitCliCommandClassifier.Risk.DENIED) {
            throw denied("CODING_USER_EXECUTION_DENIED", "CLI user execution was denied by the system Git classifier");
        }
        if (request.limits().maxStdoutBytes() != FULL_OUTPUT_BYTES_PER_CHANNEL
                || request.limits().maxStderrBytes() != FULL_OUTPUT_BYTES_PER_CHANNEL
                || !Objects.equals(request.limits().maxProcesses(), maximumProcesses)
                || request.limits().outputOverflowPolicy() != ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL
                || request.limits().timeout().compareTo(maximumTimeout) > 0) {
            throw denied("CODING_USER_EXECUTION_LIMIT_DENIED", "CLI user execution limits changed");
        }
        String workdir = request.workingDirectory().projectPath().toString();
        String digest = ExecutionRequest.digestWithScratch(
                PolicyDigest.sha256Fields(List.of(request.command().shellCommand(), workdir)), scratchSpace);
        if (!digest.equals(request.invocationDigest())) {
            throw denied("CODING_USER_EXECUTION_INTENT_DENIED", "CLI user execution digest changed");
        }
    }

    private void authorizeInternalGit(ExecutionRequest request) {
        if (request.context().sourceToolCallId().isPresent()
                || !request.context().allows("git.read")
                || request.command().mode() != ExecutionCommandMode.DIRECT) {
            throw denied("CODING_INTERNAL_GIT_DENIED", "Internal Git origin is malformed");
        }
        requireWorkspace(request, WorkspaceAccessMode.READ);
        requireFixedCommon(request, ExecutionEnvironmentRef.empty(), profileRef, ExecutionScratchSpaceSpec.none());
        List<String> argv = request.command().argv();
        if (argv.size() < 5
                || !argv.subList(0, 3).equals(List.of("git", "-c", "credential.interactive=never"))
                || !INTERNAL_GIT_SUFFIXES.contains(argv.subList(3, argv.size()))) {
            throw denied("CODING_INTERNAL_GIT_DENIED", "Internal Git command is outside the exact read allowlist");
        }
        if (!request.limits().timeout().equals(Duration.ofSeconds(15))
                || request.limits().maxStdoutBytes() != internalGitOutputBudget(argv.subList(3, argv.size()))
                || request.limits().maxStderrBytes() != 64 * 1024
                || !request.limits().maxProcesses().equals(Optional.of(4))
                || request.limits().outputOverflowPolicy() != ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL) {
            throw denied("CODING_INTERNAL_GIT_LIMIT_DENIED", "Internal Git fixed limits changed");
        }
    }

    private static int internalGitOutputBudget(List<String> suffix) {
        return suffix.equals(List.of("status", "--porcelain=v1", "--untracked-files=normal"))
                        || suffix.equals(List.of("diff", "--numstat", "HEAD", "--"))
                ? 256 * 1024
                : 4096;
    }

    private void verifyRuntimeIntent(
            ExecutionRequest execution,
            RuntimeConfigurationSnapshot configuration,
            FrozenToolBinding binding,
            ToolRequest toolRequest) {
        if (!"execution_run".equals(binding.definition().name().value())) {
            throw new SecurityException("source Tool is not the frozen execution capability");
        }
        Map<String, Object> values = toolRequest.arguments().values();
        String command = text(values, "command");
        String workspaceRef = text(values, "workspaceRef");
        String relativeWorkdir = text(values, "relativeWorkdir");
        if (!execution.workspaceId().value().equals(workspaceRef)
                || !execution.workingDirectory().projectPath().toString().equals(relativeWorkdir)
                || execution.command().mode() != ExecutionCommandMode.SHELL
                || !execution.command().shellCommand().equals(command)) {
            throw new SecurityException("execution target or command drifted from canonical Tool arguments");
        }
        if (!execution.sandboxProfileRef().equals(profileRef)
                || !execution.environmentRef().equals(environmentRef)) {
            throw new SecurityException("execution profile or environment is not product-owned");
        }
        requireFixedCommon(execution, environmentRef, profileRef, scratchSpace);
        ProjectExecutionToolOperations.validateFrozenInvocation(
                toolRequest.arguments(),
                execution,
                environmentRef,
                profileRef,
                scratchSpace,
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumProcesses);
        if (!configuration.toolBindings().contains(binding)) {
            throw new SecurityException("execution binding is not part of the frozen configuration");
        }
    }

    private void requireWorkspace(ExecutionRequest request, WorkspaceAccessMode mode) {
        workspaceAccess.require(tenant, principal, request.workspaceId(), mode);
        String workdir = request.workingDirectory().projectPath().toString();
        if (!provisioning
                .scope()
                .resolveExecutionDirectory(request.workspaceId(), workdir)
                .equals(request.workingDirectory())) {
            throw denied("CODING_EXECUTION_PATH_DENIED", "Execution workdir is not the current safe path");
        }
    }

    private void requireFixedCommon(
            ExecutionRequest request,
            ExecutionEnvironmentRef environment,
            SandboxProfileRef profile,
            ExecutionScratchSpaceSpec scratch) {
        if (!request.environmentRef().equals(environment)
                || !request.sandboxProfileRef().equals(profile)
                || !request.scratchSpace().equals(scratch)
                || !request.input().equals(ExecutionInput.none())) {
            throw denied("CODING_EXECUTION_CONFIGURATION_DENIED", "Execution fixed configuration changed");
        }
    }

    private void verifyOwner(ExecutionRequest request) {
        if (!request.context().tenant().equals(tenant)
                || !request.context().actor().equals(principal)) {
            throw denied("CODING_EXECUTION_OWNER_DENIED", "Execution owner does not match the product assembly");
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SecurityException("canonical execution argument is missing: " + key);
        }
        return text;
    }

    private static ExecutionRejectedException denied(String code, String message) {
        return new ExecutionRejectedException(code, message);
    }
}
