package io.haifa.agent.application.project.tool;

import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.access;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.invocation;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.operations;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.operationsWithWorkspaceTargetResolver;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.result;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProjectExecutionValidationTest {

    @Test
    void rejectsWorkspaceTraversalBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "representative command", "relativeWorkdir", "../outside"),
                                () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "WORKDIR_INVALID")
                .containsEntry(
                        "failureAction",
                        "Use workspaceRef with a normalized relativeWorkdir below that authorized root.");
        assertThat(invoked).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absoluteDirectoryCommands")
    void rejectsLeadingAbsoluteDirectoryChangeBeforePolicyBoundExecution(String command) {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", command, "operationFamily", "TEST"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(invoked).isFalse();
    }

    static Stream<String> absoluteDirectoryCommands() {
        return Stream.of(
                "cd /workspace && go test ./...", " cd '/home/user' && make test", "cd C:\\temp && gradlew test");
    }

    @Test
    void reportsGitDirectoryOverrideAsWorkspaceProtocolError() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        ToolResult result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of(
                                        "command", "git -C docs status --short",
                                        "operationFamily", "INSPECT"),
                                () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "PROTOCOL_ERROR")
                .containsEntry("stableFailureCode", "WORKSPACE_PROTOCOL_REQUIRED")
                .containsEntry("failureActionCode", "USE_STRUCTURED_WORKSPACE_TARGET");
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsAbsoluteWorkdirBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of(
                                        "command",
                                        "go test ./...",
                                        "relativeWorkdir",
                                        "/workspace",
                                        "operationFamily",
                                        "TEST"),
                                () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(invoked).isFalse();
    }

    @Test
    void resolvesAnExplicitWorkspaceRefBeforeDispatch() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        var operations = operationsWithWorkspaceTargetResolver(
                broker,
                1024,
                2000,
                (access, workspaceRef, relativeWorkdir) -> new WorkspacePath(
                        new WorkspaceId(workspaceRef),
                        relativeWorkdir.equals(".") ? ProjectPath.root() : ProjectPath.of(relativeWorkdir)));

        ToolResult result = operations.execute(
                invocation(
                        Map.of(
                                "command", "git status --short",
                                "workspaceRef", "workspace-attached",
                                "relativeWorkdir", "docs"),
                        () -> false),
                access());

        assertThat(result.successful()).isTrue();
        assertThat(invoked).isTrue();
    }

    @Test
    void providerAdapterDoesNotAcknowledgeKnownRejectionBeforeDispatch() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        var executor = new ProjectToolExecutor(
                (runId, principal) -> access(),
                (toolName, workspaceId, principal, runRef, arguments) -> {
                    throw new AssertionError("file operations must not run");
                },
                operations(new ProjectExecutionTestSupport.StubBroker() {}, 1024, 2000));
        var result = executor.invoke(invocation(
                Map.of("command", "git status --short", "relativeWorkdir", "C:\\outside", "operationFamily", "INSPECT"),
                () -> false,
                new ToolInvocationObserver() {
                    @Override
                    public void dispatched() {
                        dispatches.incrementAndGet();
                    }

                    @Override
                    public void acknowledged() {
                        acknowledgements.incrementAndGet();
                    }
                }));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(dispatches).hasValue(0);
        assertThat(acknowledgements).hasValue(0);
    }

    @Test
    void acceptsMissingOperationFamilyAsAnUnknownDeclaredHint() {
        java.util.concurrent.atomic.AtomicReference<ExecutionRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git status --short"), () -> false), access());

        assertThat(result.structuredData())
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("operationFamily", "UNKNOWN")
                .doesNotContainKey("declaredOperationFamily");
        assertThat(captured.get().limits().maxStdoutBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().maxStderrBytes()).isEqualTo(4096);
    }

    @Test
    void ignoresOperationHintsForAuthorizationButStillRejectsHardBoundaries() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        var operations = operations(broker, 4096, 100);

        var writeAsRead = operations.execute(
                invocation(Map.of("command", "git push origin feature", "operationFamily", "INSPECT"), () -> false),
                access());
        var tokenOverride = operations.execute(
                invocation(
                        Map.of("command", "env GH_TOKEN=value gh pr list", "operationFamily", "UNKNOWN"), () -> false),
                access());
        var statusAsDiff = operations.execute(
                invocation(Map.of("command", "git status --short", "operationFamily", "DIFF"), () -> false), access());

        assertThat(writeAsRead.structuredData())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("commandRisk", "EXTERNAL_WRITE")
                .containsEntry("commandTarget", "GIT")
                .containsEntry("effectiveOperationFamily", "MUTATE")
                .containsEntry("operationHintCode", "OPERATION_HINT_IGNORED")
                .doesNotContainKey("declaredOperationFamily");
        assertThat(tokenOverride.structuredData())
                .containsEntry("stableFailureCode", "AUTHENTICATION_OVERRIDE_DENIED")
                .containsEntry("failureActionCode", "REMOVE_AUTHENTICATION_OVERRIDE")
                .containsEntry("commandRisk", "DENIED");
        assertThat(statusAsDiff.structuredData())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("operationHintCode", "OPERATION_HINT_IGNORED")
                .containsEntry("commandOperation", "INSPECT")
                .containsEntry("commandClassificationReason", "GIT_STATUS")
                .doesNotContainKey("declaredOperationFamily");
    }
}
