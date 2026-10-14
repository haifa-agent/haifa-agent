package io.haifa.agent.execution.core.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Existing deterministic broker/provider/request fixtures shared by both script execution modes. */
final class ExecutionToolTestSupport {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    private ExecutionToolTestSupport() {}

    static ExecutionToolProvider provider(
            AtomicReference<ExecutionRequest> captured,
            Set<String> capabilities,
            TrustedWorkspacePathValidator validator) {
        return provider(captured, capabilities, validator, 0);
    }

    static ExecutionToolProvider provider(
            AtomicReference<ExecutionRequest> captured,
            Set<String> capabilities,
            TrustedWorkspacePathValidator validator,
            int exitCode) {
        return provider(
                broker(
                        request -> {
                            captured.set(request);
                            return exited(request.id(), exitCode);
                        },
                        true),
                capabilities,
                validator);
    }

    static ExecutionToolProvider provider(
            ExecutionBroker broker, Set<String> capabilities, TrustedWorkspacePathValidator validator) {
        return provider(broker, capabilities, validator, stdinRuntime());
    }

    static ExecutionToolProvider provider(
            ExecutionBroker broker,
            Set<String> capabilities,
            TrustedWorkspacePathValidator validator,
            ScriptRuntimeAdapter adapter) {
        var runtimes = new ScriptRuntimeResolver(ExecutionOperatingSystem.LINUX, List.of(adapter));
        var configuration = new ExecutionToolConfiguration(
                ExecutionEnvironmentRef.empty(),
                new SandboxProfileRef("sandbox", "1"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                runtimes,
                ignored -> {},
                value -> value);
        return new ExecutionToolProvider(
                broker,
                () -> "execution-id",
                () -> NOW,
                ignored -> new ExecutionInvocationScopeResolver.ExecutionInvocationScope(
                        new WorkspaceId("workspace"), capabilities),
                configuration,
                validator);
    }

    static ExecutionBroker broker(
            java.util.function.Function<ExecutionRequest, ExecutionResult> execute, boolean cancellable) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                return execute.apply(request);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return cancellable;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
    }

    static ScriptRuntimeAdapter stdinRuntime() {
        return new ScriptRuntimeAdapter() {
            @Override
            public String language() {
                return "fixture-runtime";
            }

            @Override
            public String executable() {
                return "trusted-runtime";
            }

            @Override
            public PreparedScript prepare(String content, List<String> arguments) {
                var argv = new java.util.ArrayList<String>();
                argv.add(executable());
                argv.addAll(arguments);
                return new PreparedScript(
                        ExecutionCommand.direct(argv),
                        ExecutionInput.utf8(content.endsWith("\n") ? content : content + "\n"));
            }
        };
    }

    static ToolInvocationRequest invocation(FrozenToolBinding binding) {
        return invocation(binding, ToolInvocationObserver.noop());
    }

    static ToolInvocationRequest invocation(FrozenToolBinding binding, ToolInvocationObserver observer) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("call"),
                new AgentRunId("run"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "human"),
                new ToolArguments("fixture.input", "1.0.0", Map.of("value", "MODEL_VALUE")),
                NOW.plusSeconds(30),
                Optional.of("idempotency"),
                () -> false,
                Map.of(),
                observer);
    }

    static ExecutionResult exited(ExecutionId id, int exitCode) {
        var output = new ExecutionOutput("ok", null, 2, "0".repeat(64), false, false);
        var empty = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        return new ExecutionResult(
                id,
                ExecutionStatus.EXITED,
                exitCode,
                NOW,
                NOW.plusMillis(10),
                output,
                empty,
                "sandbox-session",
                new ResourceUsageSummary(Duration.ofMillis(10), 1),
                null,
                false);
    }
}
