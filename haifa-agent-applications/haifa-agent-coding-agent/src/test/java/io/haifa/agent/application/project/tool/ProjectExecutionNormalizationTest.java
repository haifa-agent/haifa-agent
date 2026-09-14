package io.haifa.agent.application.project.tool;

import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.WORKSPACE_ID;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.access;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.chunk;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.invocation;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.operations;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.result;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.resultWithFailure;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.resultWithoutChangeSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionProcessIdentity;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProjectExecutionNormalizationTest {

    @Test
    void constructsTrustedShellRequestAndMapsBoundedStructuredResult() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onStarted(new ExecutionProcessIdentity(990));
                observer.onOutput(chunk("\u001B[31mfirst\u001B[0m\nsecond\nthird\n"));
                return result(request.id(), ExecutionStatus.EXITED, 7);
            }
        };
        var operations = operations(broker, 1024, 2);

        var result = operations.execute(
                invocation(
                        Map.of(
                                "command", "printf 'first\\nsecond\\nthird\\n' | cat > result.txt",
                                "relativeWorkdir", "src",
                                "timeoutMillis", 5000,
                                "operationFamily", "TEST",
                                "description", "Write representative output"),
                        () -> false),
                access());

        assertThat(captured.get().command().mode()).isEqualTo(ExecutionCommandMode.SHELL);
        assertThat(captured.get().command().shellCommand()).contains("| cat > result.txt");
        assertThat(captured.get().workingDirectory().projectPath().value()).isEqualTo("src");
        assertThat(captured.get().limits().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(captured.get().context().frozenCapabilities()).contains("execution_run");
        assertThat(captured.get().scratchSpace().isEmpty()).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.summary())
                .contains("Command exited (exit 7)", "first", "1 lines omitted", "third")
                .doesNotContain("second");
        assertThat(result.structuredData())
                .containsEntry("toolCallId", "tool-call-1")
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 7)
                .containsEntry("truncated", true)
                .containsEntry("outputIncomplete", false)
                .containsEntry("outputRef", "stdout-asset")
                .containsEntry("operationFamily", "TEST")
                .doesNotContainKeys("failureCategory", "stableFailureCode", "failureCode")
                .doesNotContainKey("scratchSpecDigest")
                .doesNotContainKey("scratchProvisioned")
                .doesNotContainKey("scratchCleanupFailed");
        assertThat(result.structuredData()).doesNotContainKeys("validationEvidence", "validationAttemptRef");
        assertThat(result.assets()).extracting(AssetRef::assetId).containsExactly("stdout-asset");
    }

    @Test
    void usesProductMaximumCappedByInvocationDeadlineWhenTimeoutIsOmitted() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.EXITED, 0);
            }
        };
        String command = "mvn -pl :module test";
        var candidate = new CodingVerificationCandidate(
                command,
                CodingVerificationCost.MEDIUM,
                CodingVerificationTrigger.MODULE_CHANGE,
                CodingVerificationSource.REPOSITORY_INSTRUCTIONS,
                "AGENTS.md",
                CodingValidationScope.SELECTED);
        var frozen = CodingSessionVerificationConfiguration.freeze(new CodingVerificationProfile(List.of(candidate)));

        ToolResult result = operations(broker, 1024, 100, ignored -> frozen)
                .execute(invocation(Map.of("command", command), () -> false), access());

        assertThat(captured.get().limits().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(result.structuredData())
                .doesNotContainKeys(
                        "timeoutSelection", "selectedTimeoutMillis", "effectiveTimeoutMillis", "deadlineCapped");
    }

    @Test
    void treatsTimedOutExecutionWithCompletedWorkspaceObservationAsTerminalWithoutReplaying() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<ExecutionResult> completed = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                executions.incrementAndGet();
                observer.onStarted(new ExecutionProcessIdentity(991));
                ExecutionResult result = resultWithoutChangeSet(request.id(), ExecutionStatus.TIMED_OUT, null);
                completed.set(result);
                return result;
            }

            @Override
            public Optional<ExecutionResult> findByIdempotencyKey(String idempotencyKey) {
                return Optional.ofNullable(completed.get());
            }
        };
        var operations = operations(broker, 4096, 100);
        ToolInvocationRequest invocation = invocation(
                Map.of("command", "generate-file > generated.txt", "operationFamily", "MUTATE"), () -> false);
        ToolResult observed = operations.execute(invocation, access());

        var reconciled = operations.reconcile(
                new ToolReconciliationRequest(
                        invocation.binding(),
                        invocation.toolCallId(),
                        invocation.runId(),
                        invocation.tenant(),
                        invocation.principal(),
                        invocation.arguments(),
                        invocation.idempotencyKey().orElseThrow(),
                        Optional.of(new ToolDispatchEvidence(
                                completed.get().id().value(),
                                OptionalLong.of(991),
                                PolicyDigest.sha256Fields(
                                        List.of("execution-working-directory-v1", WORKSPACE_ID.value(), ".")))),
                        Optional.of(observed)),
                access());

        assertThat(executions).hasValue(1);
        assertThat(observed.structuredData()).doesNotContainKey("runtimeOutcome");
        assertThat(observed.structuredData())
                .containsEntry("processState", "TIMED_OUT")
                .containsEntry("durationMillis", 1000L)
                .containsEntry("failureCategory", "TIMEOUT")
                .containsEntry("stableFailureCode", "TIMEOUT")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY")
                .containsKey("output");
        assertThat(reconciled.status()).isEqualTo(ToolReconciliationStatus.RESOLVED);
        assertThat(reconciled.reasonCode()).isEqualTo("EXECUTION_TERMINAL_AND_WORKSPACE_OBSERVATION_CONFIRMED");
        assertThat(reconciled.result()).hasValueSatisfying(result -> {
            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData())
                    .containsEntry("processState", "TIMED_OUT")
                    .containsEntry("reconcileStatus", "RESOLVED")
                    .containsEntry("replayAllowed", false);
        });
    }

    @Test
    void handlesTimeoutWithClearDiagnosticMessage() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.TIMED_OUT, null);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "go test ./..."), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData().get("processState")).isEqualTo("TIMED_OUT");
        assertThat(result.summary()).contains("Command timed out");
        assertThat(result.structuredData().get("output").toString()).contains("Command timed out");
    }

    @Test
    void preservesKnownExitWhenOutputDrainIsIncomplete() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                ExecutionResult base = result(request.id(), ExecutionStatus.EXITED, 9);
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
                        base.replayed(),
                        base.scratchProvisioned(),
                        base.scratchCleanupFailed(),
                        true);
            }
        };

        ToolResult result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "bounded-output"), () -> false), access());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 9)
                .containsEntry("outputIncomplete", true)
                .doesNotContainKey("runtimeOutcome");
    }

    @Test
    void exposesTimeoutTreeUnconfirmedWithoutMakingItReplayable() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                ExecutionResult base = resultWithFailure(
                        request.id(),
                        ExecutionStatus.UNKNOWN,
                        new ExecutionFailure("TIMEOUT_TREE_UNCONFIRMED", "process tree termination was not confirmed"));
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
                        base.replayed(),
                        base.scratchProvisioned(),
                        base.scratchCleanupFailed(),
                        true);
            }
        };

        ToolResult result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "side-effecting"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("processState", "UNKNOWN")
                .containsEntry("runtimeOutcome", "OUTCOME_UNKNOWN")
                .containsEntry("outputIncomplete", true)
                .containsEntry("stableFailureCode", "TIMEOUT_TREE_UNCONFIRMED")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY");
    }

    @Test
    void preservesCancellationAndUnknownOutcomeAsDistinctExecutionFacts() {
        AtomicInteger invocation = new AtomicInteger();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                ExecutionStatus status =
                        invocation.getAndIncrement() == 0 ? ExecutionStatus.CANCELLED : ExecutionStatus.UNKNOWN;
                return resultWithFailure(
                        request.id(),
                        status,
                        new ExecutionFailure(
                                status == ExecutionStatus.CANCELLED ? "CANCELLED" : "OUTCOME_UNKNOWN",
                                "bounded execution fact"));
            }
        };
        var operations = operations(broker, 4096, 100);

        ToolResult cancelled = operations.execute(
                invocation(Map.of("command", "long-running", "operationFamily", "TEST"), () -> false), access());
        ToolResult unknown = operations.execute(
                invocation(Map.of("command", "side-effecting", "operationFamily", "MUTATE"), () -> false), access());

        assertThat(cancelled.structuredData())
                .containsEntry("processState", "CANCELLED")
                .containsEntry("failureCategory", "CANCELLED")
                .containsEntry("stableFailureCode", "CANCELLED")
                .containsEntry("failureActionCode", "DO_NOT_AUTOMATICALLY_RETRY")
                .doesNotContainKey("exitCode");
        assertThat(unknown.structuredData())
                .containsEntry("processState", "UNKNOWN")
                .containsEntry("runtimeOutcome", "OUTCOME_UNKNOWN")
                .containsEntry("failureCategory", "OUTCOME_UNKNOWN")
                .containsEntry("stableFailureCode", "OUTCOME_UNKNOWN")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY")
                .doesNotContainKey("exitCode");
    }

    @Test
    void exposesConfirmedProcessLimitAsAStableResourceFailure() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return resultWithFailure(
                        request.id(),
                        ExecutionStatus.PROCESS_LIMIT_EXCEEDED,
                        new ExecutionFailure("PROCESS_LIMIT_EXCEEDED", "process count exceeded its budget"));
            }
        };

        ToolResult result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "mvn test", "operationFamily", "TEST"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("processState", "PROCESS_LIMIT_EXCEEDED")
                .containsEntry("failureCategory", "PROCESS_LIMIT")
                .containsEntry("stableFailureCode", "PROCESS_LIMIT_EXCEEDED")
                .containsEntry("observedProcessCount", 1)
                .doesNotContainKey("runtimeOutcome");
    }

    @Test
    void exposesOutputLimitAsAStableExecutionOutcomeWithNarrowingAdvice() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return resultWithFailure(
                        request.id(),
                        ExecutionStatus.OUTPUT_LIMIT_EXCEEDED,
                        new ExecutionFailure("OUTPUT_LIMIT_EXCEEDED", "output exceeded its bounded capture"));
            }
        };

        ToolResult result = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "rg broad-pattern", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("processState", "OUTPUT_LIMIT_EXCEEDED")
                .containsEntry("failureCategory", "OUTPUT_LIMIT")
                .containsEntry("stableFailureCode", "OUTPUT_LIMIT_EXCEEDED")
                .containsEntry("resourceClass", "OUTPUT")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY");
        assertThat(result.structuredData().get("failureAction").toString())
                .contains("narrower fields", "smaller result limit");
    }

    @Test
    void deliversNormalNonzeroExitCodesWithoutADeclaration() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (calls.getAndIncrement() == 0) {
                    observer.onOutput(chunk("diff --git a/src/A.java b/src/A.java\n@@ -1 +1 @@\n"));
                }
                return result(request.id(), ExecutionStatus.EXITED, 1);
            }
        };

        var differences = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "git diff --exit-code", "operationFamily", "DIFF"), () -> false),
                        access());
        var noMatches = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "git grep missing", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(differences.successful()).isTrue();
        assertThat(differences.summary())
                .contains("Command exited (exit 1)", "observedFiles=1", "observedHunks=1")
                .doesNotContain("diff --git", "@@ -1 +1 @@");
        assertThat(differences.structuredData())
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 1)
                .containsEntry("outputBudgetFamily", "DIFF")
                .containsEntry("outputBudgetBytesPerChannel", 16_384)
                .containsEntry("modelOutputBudgetBytes", 4096)
                .containsEntry("modelOutputBudgetLines", 100)
                .containsEntry("diffFileCount", 1L)
                .containsEntry("diffHunkCount", 1L)
                .containsEntry("diffCountsComplete", true)
                .containsEntry("diffSummary", "observedFiles=1, observedHunks=1, countsComplete=true")
                .doesNotContainKeys(
                        "failureCategory", "stableFailureCode", "failureCode", "runtimeOutcome", "reconcileStatus");
        assertThat(noMatches.successful()).isTrue();
        assertThat(noMatches.structuredData())
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 1);
    }

    @Test
    void deliversUndeclaredNonzeroExitCodesAsCompletedToolResults() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.EXITED, 1);
            }
        };

        var noMatches = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command",
                                        "rg needle . | Select-Object -First 20",
                                        "operationFamily",
                                        "INSPECT"),
                                () -> false),
                        access());

        assertThat(noMatches.successful()).isTrue();
        assertThat(noMatches.summary()).contains("Command exited (exit 1)");
        assertThat(noMatches.structuredData())
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 1)
                .doesNotContainKeys("status", "failureCategory", "stableFailureCode", "failureCode");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classifiedCommandFailures")
    void classifiesCommandFailuresAccordingToStandardRules(
            String caseName,
            String command,
            String output,
            ExecutionStatus status,
            Integer exitCode,
            ExecutionFailure failure,
            String expectedCategory,
            String expectedStableCode,
            String expectedResourceClass,
            String expectedActionSubstring) {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (output != null) {
                    observer.onOutput(chunk(output));
                }
                return failure != null
                        ? resultWithFailure(request.id(), status, failure)
                        : result(request.id(), status, exitCode);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", command, "operationFamily", "INSPECT"), () -> false), access());

        assertThat(result.successful()).isFalse();
        if (expectedCategory != null) {
            assertThat(result.structuredData()).containsEntry("failureCategory", expectedCategory);
        }
        assertThat(result.structuredData()).containsEntry("stableFailureCode", expectedStableCode);
        if (expectedResourceClass != null) {
            assertThat(result.structuredData()).containsEntry("resourceClass", expectedResourceClass);
        }
        if (expectedActionSubstring != null) {
            assertThat(result.structuredData().get("failureAction").toString()).contains(expectedActionSubstring);
        }
    }

    static Stream<Arguments> classifiedCommandFailures() {
        return Stream.of(
                Arguments.of(
                        "executable not found launcher evidence",
                        "fast-search needle",
                        "bounded launcher diagnostic\n",
                        ExecutionStatus.FAILED,
                        null,
                        new ExecutionFailure("EXECUTABLE_NOT_FOUND", "configured executable was not found"),
                        "DEPENDENCY_UNAVAILABLE",
                        "EXECUTABLE_NOT_FOUND",
                        "TOOLCHAIN",
                        null),
                Arguments.of(
                        "github cli command not found",
                        "gh pr list --repo owner/repo",
                        "gh: command not found\n",
                        ExecutionStatus.FAILED,
                        null,
                        new ExecutionFailure("EXECUTABLE_NOT_FOUND", "configured executable was not found"),
                        null,
                        "GH_CLI_UNAVAILABLE",
                        null,
                        "Install GitHub CLI"));
    }

    @Test
    void reportsRiskEscalationWithoutInterpretingNormalNetworkCommandExit() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (calls.getAndIncrement() < 2) return result(request.id(), ExecutionStatus.EXITED, 0);
                observer.onOutput(chunk("fatal: unable to access remote: Could not resolve host\n"));
                return result(request.id(), ExecutionStatus.EXITED, 128);
            }
        };
        var operations = operations(broker, 4096, 100);

        var compound = operations.execute(
                invocation(Map.of("command", "git status && git log -1", "operationFamily", "INSPECT"), () -> false),
                access());
        var unknownGit = operations.execute(
                invocation(Map.of("command", "git frobnicate", "operationFamily", "UNKNOWN"), () -> false), access());
        var network = operations.execute(
                invocation(Map.of("command", "git ls-remote origin", "operationFamily", "INSPECT"), () -> false),
                access());

        assertThat(compound.successful()).isTrue();
        assertThat(compound.structuredData())
                .containsEntry("effectiveRisk", "HIGH")
                .containsEntry("riskResolutionCode", "COMMAND_RISK_ESCALATED")
                .containsEntry("operationHintCode", "OPERATION_HINT_UNVERIFIED");
        assertThat(unknownGit.successful()).isTrue();
        assertThat(unknownGit.structuredData())
                .containsEntry("effectiveRisk", "HIGH")
                .containsEntry("riskResolutionCode", "GIT_COMMAND_UNKNOWN_HIGH_RISK");
        assertThat(network.successful()).isTrue();
        assertThat(network.structuredData())
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 128)
                .doesNotContainKeys("stableFailureCode", "failureActionCode", "failureCategory");
    }

    @Test
    void unknownGenericCommandsRemainInsideTheBroadOutputBudget() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.EXITED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "custom-tool --all", "operationFamily", "UNKNOWN"), () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("outputBudgetFamily", "UNKNOWN")
                .containsEntry("outputBudgetBytesPerChannel", 32_768);
        assertThat(captured.get().limits().maxStdoutBytes()).isEqualTo(32_768);
        assertThat(captured.get().limits().maxStderrBytes()).isEqualTo(32_768);
    }

    @Test
    void sendsCompoundGitCommandsToTheBrokerAsHighRiskInsteadOfRejectingShellComposition() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.EXITED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command", "git status --short && git diff --stat",
                                        "operationFamily", "INSPECT"),
                                () -> false),
                        access());

        assertThat(captured.get().command().shellCommand()).contains("&&");
        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("commandTarget", "GIT")
                .containsEntry("commandRisk", "UNKNOWN")
                .containsEntry("effectiveRisk", "HIGH")
                .containsEntry("commandOperation", "UNKNOWN")
                .containsEntry("commandClassificationReason", "COMPOUND_OR_WRAPPED_COMMAND")
                .containsEntry("riskResolverVersion", "2");
    }

    @Test
    void propagatesToolCancellationToTheBroker() throws Exception {
        AtomicBoolean cancellation = new AtomicBoolean();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
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
        var future = CompletableFuture.supplyAsync(() -> operations.execute(
                invocation(Map.of("command", "long-running representative command"), cancellation::get), access()));

        assertThat(executing.await(1, TimeUnit.SECONDS)).isTrue();
        cancellation.set(true);

        assertThat(future.get(3, TimeUnit.SECONDS).structuredData()).containsEntry("processState", "CANCELLED");
        assertThat(cancelled.getCount()).isZero();
    }

    @Test
    void marksDispatchOnlyAfterTheBrokerReportsProcessStart() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                assertThat(dispatches).hasValue(0);
                assertThat(acknowledgements).hasValue(0);
                observer.onStarted();
                assertThat(dispatches).hasValue(1);
                assertThat(acknowledgements).hasValue(0);
                return result(request.id(), ExecutionStatus.EXITED, 0);
            }
        };

        operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "representative command"), () -> false, new ToolInvocationObserver() {
                                    @Override
                                    public void dispatched() {
                                        dispatches.incrementAndGet();
                                    }

                                    @Override
                                    public void acknowledged() {
                                        acknowledgements.incrementAndGet();
                                    }
                                }),
                        access());

        assertThat(dispatches).hasValue(1);
        assertThat(acknowledgements).hasValue(1);
    }

    @Test
    void raisesTrustedNotDispatchedFailureOnlyForEligibleDirectGitPreflight() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "NETWORK_DENIED", "network is unavailable before process dispatch", null);
            }
        };

        assertThatThrownBy(() -> operations(broker, 1024, 2000)
                        .execute(invocation(Map.of("command", "git ls-remote origin"), () -> false), access()))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(failure -> {
                    var invocation = (ToolInvocationException) failure;
                    assertThat(invocation.failureCode()).isEqualTo("NETWORK_PERMISSION_REQUIRED");
                    assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directGitAuthenticationPreflights")
    void mapsDirectGitAndGithubAuthenticationPreflightToProductSpecificRecoveryCodes(
            String caseName, String command, String expectedFailureCode) {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "AUTHENTICATION_UNAVAILABLE", "authentication failed before process dispatch", null);
            }
        };
        ProjectExecutionToolOperations operations = operations(broker, 1024, 2000);

        assertThatThrownBy(() -> operations.execute(invocation(Map.of("command", command), () -> false), access()))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(failure -> assertThat(((ToolInvocationException) failure).failureCode())
                        .isEqualTo(expectedFailureCode));
    }

    static Stream<Arguments> directGitAuthenticationPreflights() {
        return Stream.of(
                Arguments.of("git fetch origin", "git fetch origin", "GIT_AUTHENTICATION_UNAVAILABLE"),
                Arguments.of("gh repo view", "gh repo view", "GH_AUTHENTICATION_UNAVAILABLE"));
    }

    @Test
    void leavesOtherTargetPreflightFailureAsOrdinaryUnsuccessfulResult() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "NETWORK_DENIED", "network is unavailable before process dispatch", null);
            }
        };

        ToolResult result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "curl https://example.invalid"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "NETWORK_UNAVAILABLE");
    }

    @Test
    void leavesGenericHostAuthenticationPreflightFailureIneligibleForDirectGitRecovery() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "HOST_AUTHENTICATION_UNAVAILABLE", "host authentication is unavailable", null);
            }
        };

        ToolResult result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "git ls-remote origin"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "HOST_AUTHENTICATION_UNAVAILABLE");
    }

    @Test
    void preservesStableSandboxFailureCodeAsFailedToolResult() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new SandboxException("SANDBOX_PROVISION_FAILED", "sandbox setup failed");
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "git status --short"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData().get("processState")).isEqualTo("FAILED");
        assertThat(result.structuredData().get("failureCode")).isEqualTo("SANDBOX_PROVISION_FAILED");
        assertThat(result.structuredData().get("output").toString()).contains("sandbox setup failed");
    }
}
