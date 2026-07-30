package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommandMode;
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
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectExecutionToolOperationsTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-execution-tool");

    @Test
    void constructsTrustedShellRequestAndMapsBoundedStructuredResult() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("\u001B[31mfirst\u001B[0m\nsecond\nthird\n"));
                return result(request.id(), ExecutionStatus.FAILED, 7);
            }
        };
        var operations = operations(broker, 1024, 2);

        var result = operations.execute(
                invocation(
                        Map.of(
                                "command", "printf 'first\\nsecond\\nthird\\n' | cat > result.txt",
                                "workdir", "src",
                                "timeoutMillis", 5000,
                                "operationFamily", "TEST",
                                "description", "Write representative output"),
                        () -> false),
                access());

        assertThat(captured.get().command().mode()).isEqualTo(ExecutionCommandMode.SHELL);
        assertThat(captured.get().command().shellCommand()).contains("| cat > result.txt");
        assertThat(captured.get().workingDirectory().projectPath().value()).isEqualTo("src");
        assertThat(captured.get().limits().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(captured.get().context().frozenCapabilities()).contains("execution.run");
        assertThat(captured.get().scratchSpace()).isEqualTo(CodingToolchainEnvironmentProfile.defaultScratchSpace());
        assertThat(captured.get().scratchSpace().rootEnvironmentNames()).contains("GOTMPDIR");
        assertThat(captured.get().scratchSpace().childBindings())
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.environmentName()).isEqualTo("GOCACHE");
                    assertThat(binding.relativeDirectory()).isEqualTo("go-build");
                });
        assertThat(result.successful()).isFalse();
        assertThat(result.summary())
                .contains("Command failed (exit 7)", "second", "third")
                .doesNotContain("first");
        assertThat(result.structuredData())
                .containsEntry("status", "FAILED")
                .containsEntry("exitCode", 7)
                .containsEntry("truncated", true)
                .containsEntry("outputRef", "stdout-asset")
                .containsEntry("fileChangeSetId", "changes-1")
                .containsEntry("failureCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("operationFamily", "TEST")
                .containsEntry("failureCategory", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("resourceClass", "COMMAND")
                .containsEntry(
                        "scratchSpecDigest",
                        CodingToolchainEnvironmentProfile.defaultScratchSpace().canonicalDigest());
        assertThat(result.assets()).extracting(AssetRef::assetId).containsExactly("stdout-asset");
    }

    @Test
    void propagatesToolCancellationToTheBroker() throws Exception {
        AtomicBoolean cancellation = new AtomicBoolean();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ExecutionBroker broker = new StubBroker() {
            private ExecutionId active;

            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                active = request.id();
                executing.countDown();
                try {
                    assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return result(request.id(), ExecutionStatus.CANCELLED, null);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                if (!id.equals(active)) return false;
                cancelled.countDown();
                return true;
            }
        };
        var operations = operations(broker, 1024, 2000);
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> operations.execute(
                invocation(Map.of("command", "long-running representative command"), cancellation::get), access()));

        assertThat(executing.await(1, TimeUnit.SECONDS)).isTrue();
        cancellation.set(true);

        assertThat(future.get(3, TimeUnit.SECONDS).structuredData()).containsEntry("status", "CANCELLED");
        assertThat(cancelled.getCount()).isZero();
    }

    @Test
    void rejectsWorkspaceTraversalBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(Map.of("command", "representative command", "workdir", "../outside"), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "WORKDIR_INVALID");
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsLeadingAbsoluteDirectoryChangeBeforePolicyBoundExecution() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        for (String command : List.of(
                "cd /workspace && go test ./...", " cd '/home/user' && make test", "cd C:\\temp && gradlew test")) {
            var result = operations(broker, 1024, 2000)
                    .execute(invocation(Map.of("command", command, "operationFamily", "TEST"), () -> false), access());

            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData())
                    .containsEntry("failureCategory", "INVALID_INPUT")
                    .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        }
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsAbsoluteWorkdirBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "go test ./...", "workdir", "/workspace", "operationFamily", "TEST"),
                                () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(invoked).isFalse();
    }

    @Test
    void userInitiatedCommandUsesTheSameBrokerAndPolicyReference() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("terminal output"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .executeUserInitiated(
                        new AgentRunId("terminal-audit-1"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("operator", "user"),
                        access(),
                        "git status --short",
                        ".",
                        Duration.ofSeconds(5),
                        "terminal-key",
                        "policy-terminal-1");

        assertThat(captured.get().context().policyDecisionRef()).isEqualTo("policy-terminal-1");
        assertThat(captured.get().context().runRef()).isEqualTo("terminal-audit-1");
        assertThat(captured.get().workingDirectory().projectPath().isRoot()).isTrue();
        assertThat(result.summary()).contains("Command succeeded", "terminal output");
    }

    @Test
    void sanitizesExecutionOutputBeforeReturningItToTheModel() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("failure at D:\\private\\workspace\\src\\Main.java\n"));
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };
        var operations = new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                1024,
                2000,
                8,
                ExecutionOutputObserver.noop(),
                value -> value.replace("D:\\private\\workspace", "<workspace>"));

        var result = operations.execute(
                invocation(Map.of("command", "representative failing command"), () -> false), access());

        assertThat(result.summary()).contains("<workspace>\\src\\Main.java").doesNotContain("D:\\private\\workspace");
        assertThat(result.structuredData().get("output").toString())
                .contains("<workspace>\\src\\Main.java")
                .doesNotContain("D:\\private\\workspace");
    }

    private static ProjectExecutionToolOperations operations(
            ExecutionBroker broker, int maximumOutputBytes, int maximumOutputLines) {
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
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace());
    }

    private static ToolInvocationRequest invocation(
            Map<String, Object> arguments, io.haifa.agent.tool.api.ToolCancellation cancellation) {
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
                Optional.of("policy-1"),
                cancellation,
                List.of(),
                io.haifa.agent.tool.api.ToolInvocationObserver.noop());
    }

    private static RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(WORKSPACE_ID, Set.of("execution.run"));
    }

    private static io.haifa.agent.sandbox.api.SandboxProfile executionProfile() {
        return new io.haifa.agent.sandbox.api.SandboxProfile(
                new SandboxProfileRef("shell", "1"),
                "host-guarded",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("test")),
                Set.of(),
                Set.of(),
                true,
                io.haifa.agent.sandbox.api.NetworkPolicy.ALLOW,
                io.haifa.agent.sandbox.api.SandboxFilesystemPolicy.hostCompatible(),
                new io.haifa.agent.sandbox.api.SandboxCapabilities(true, false, false, false, false));
    }

    private static ProcessOutputChunk chunk(String value) {
        return new ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT, value.getBytes(StandardCharsets.UTF_8), false, false);
    }

    private static ExecutionResult result(ExecutionId id, ExecutionStatus status, Integer exitCode) {
        var asset = new AssetRef("stdout-asset", "text/plain", "stdout.txt");
        return new ExecutionResult(
                id,
                status,
                exitCode,
                NOW,
                NOW.plusSeconds(1),
                new ExecutionOutput("stored stdout", asset, 13, "sha-stdout", false, false),
                new ExecutionOutput("", null, 0, "sha-stderr", false, false),
                new FileChangeSetId("changes-1"),
                "session-1",
                new ResourceUsageSummary(Duration.ofSeconds(1), 1),
                status == ExecutionStatus.FAILED
                        ? new ExecutionFailure("PROCESS_EXIT_NONZERO", "process exited with a non-zero code")
                        : null,
                false);
    }

    private static ToolProvider provider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest request) {
                throw new AssertionError("catalog-only provider");
            }
        };
    }

    private abstract static class StubBroker implements ExecutionBroker {
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
