package io.haifa.agent.application.project.tool;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

final class ProjectExecutionTestSupport {
    static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-execution-tool");

    private ProjectExecutionTestSupport() {}

    static ProjectExecutionToolOperations operations(
            ExecutionBroker broker, int maximumOutputBytes, int maximumOutputLines) {
        return operations(broker, maximumOutputBytes, maximumOutputLines, CodingVerificationProfileProvider.empty());
    }

    static ProjectExecutionToolOperations operations(
            ExecutionBroker broker,
            int maximumOutputBytes,
            int maximumOutputLines,
            CodingVerificationProfileProvider verificationProfiles) {
        return new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                maximumOutputBytes,
                maximumOutputLines,
                8,
                ExecutionOutputObserver.noop(),
                UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                verificationProfiles);
    }

    static ProjectExecutionToolOperations operations(
            ExecutionBroker broker,
            int maximumOutputBytes,
            int maximumOutputLines,
            CodingVerificationProfileProvider verificationProfiles,
            ExecutionRepositoryBaselineObserver repositoryBaselines) {
        return new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                maximumOutputBytes,
                maximumOutputLines,
                8,
                ExecutionOutputObserver.noop(),
                UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                verificationProfiles,
                repositoryBaselines);
    }

    static ProjectExecutionToolOperations operationsWithSanitizer(
            ExecutionBroker broker,
            int maximumOutputBytes,
            int maximumOutputLines,
            UnaryOperator<String> outputSanitizer) {
        return new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                maximumOutputBytes,
                maximumOutputLines,
                8,
                ExecutionOutputObserver.noop(),
                outputSanitizer,
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                CodingVerificationProfileProvider.empty());
    }

    static ProjectExecutionToolOperations operationsWithWorkspaceTargetResolver(
            ExecutionBroker broker,
            int maximumOutputBytes,
            int maximumOutputLines,
            ExecutionWorkspaceTargetResolver workspaceTargetResolver) {
        return new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                maximumOutputBytes,
                maximumOutputLines,
                8,
                ExecutionOutputObserver.noop(),
                UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                workspaceTargetResolver,
                CodingVerificationProfileProvider.empty());
    }

    static ToolInvocationRequest invocation(Map<String, Object> arguments, ToolCancellation cancellation) {
        return invocation(arguments, cancellation, ToolInvocationObserver.noop());
    }

    static ToolInvocationRequest invocation(
            Map<String, Object> arguments, ToolCancellation cancellation, ToolInvocationObserver observer) {
        arguments = executionArguments(arguments);
        var binding = new ProjectToolCatalog()
                .freeze(Set.of("execution.run"), Set.of("execution.run"), true, provider(), executionProfile())
                .snapshot()
                .bindings()
                .getFirst();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("operator", "user"),
                new ToolArguments("haifa.execution.run.input", "1.0.0", arguments),
                NOW.plusSeconds(30),
                Optional.of("execution-key"),
                cancellation,
                Map.of(),
                observer);
    }

    static RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(WORKSPACE_ID, Set.of("execution.run"));
    }

    static RunWorkspaceAccess access(WorkspaceId workspaceId) {
        return new RunWorkspaceAccess(workspaceId, Set.of("execution.run"));
    }

    static Map<String, Object> executionArguments(Map<String, Object> arguments) {
        return executionArguments(arguments, WORKSPACE_ID);
    }

    static Map<String, Object> executionArguments(Map<String, Object> arguments, WorkspaceId workspaceId) {
        var values = new LinkedHashMap<String, Object>(arguments);
        values.putIfAbsent("workspaceRef", workspaceId.value());
        values.putIfAbsent("relativeWorkdir", ".");
        return Map.copyOf(values);
    }

    static SandboxProfile executionProfile() {
        return new SandboxProfile(
                new SandboxProfileRef("shell", "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of("test")),
                Set.of(),
                Set.of(),
                true);
    }

    static SandboxProfile deniedExecutionProfile() {
        return new SandboxProfile(
                new SandboxProfileRef("shell-denied", "1"),
                "local-native",
                SandboxConfigurationDigest.sha256Fields(List.of("denied")),
                Set.of(),
                Set.of(),
                true);
    }

    static ProcessOutputChunk chunk(String value) {
        return new ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT, value.getBytes(StandardCharsets.UTF_8), false, false);
    }

    static ExecutionResult result(ExecutionId id, ExecutionStatus status, Integer exitCode) {
        var asset = new AssetRef("stdout-asset", "text/plain", "stdout.txt");
        return new ExecutionResult(
                id,
                status,
                exitCode,
                NOW,
                NOW.plusSeconds(1),
                new ExecutionOutput("stored stdout", asset, 13, "sha-stdout", false, false),
                new ExecutionOutput("", null, 0, "sha-stderr", false, false),
                "session-1",
                new ResourceUsageSummary(Duration.ofSeconds(1), 1),
                status == ExecutionStatus.FAILED
                        ? new ExecutionFailure("NON_ZERO_EXIT", "process exited with a non-zero code")
                        : null,
                false);
    }

    static ExecutionResult resultWithoutChangeSet(ExecutionId id, ExecutionStatus status, Integer exitCode) {
        var base = result(id, status, exitCode);
        return new ExecutionResult(
                base.id(),
                base.status(),
                base.exitCode(),
                base.startedAt(),
                base.endedAt(),
                base.stdout(),
                base.stderr(),
                base.sandboxSessionRef(),
                base.resourceUsage(),
                base.failure(),
                base.replayed());
    }

    static ExecutionResult resultWithFailure(ExecutionId id, ExecutionStatus status, ExecutionFailure failure) {
        var base = result(id, status, null);
        return new ExecutionResult(
                base.id(),
                base.status(),
                base.exitCode(),
                base.startedAt(),
                base.endedAt(),
                base.stdout(),
                base.stderr(),
                base.sandboxSessionRef(),
                base.resourceUsage(),
                failure,
                base.replayed());
    }

    static ToolProvider provider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                throw new AssertionError("catalog-only provider");
            }
        };
    }

    abstract static class StubBroker implements ExecutionBroker {
        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            return execute(request, ExecutionOutputObserver.noop());
        }

        @Override
        public boolean cancel(ExecutionId id) {
            return false;
        }

        @Override
        public Optional<ExecutionResult> find(ExecutionId id) {
            return Optional.empty();
        }
    }
}
