package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import io.haifa.agent.application.project.product.coding.delivery.RepositoryBaselineUnavailableException;
import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.recovery.ExecutionRecoveryKeys;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectExecutionToolOperationsTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-execution-tool");

    @Test
    void establishesExecutionBaselineBeforeDispatchAndInvalidatesAfterCompletion() {
        List<String> order = new java.util.ArrayList<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                order.add("dispatch");
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        ExecutionRepositoryBaselineObserver baselines = new ExecutionRepositoryBaselineObserver() {
            @Override
            public void beforeDispatch(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                order.add("before");
            }

            @Override
            public void afterCompletion(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                order.add("after");
            }
        };

        ToolResult result = operations(broker, 1024, 100, CodingVerificationProfileProvider.empty(), baselines)
                .execute(invocation(Map.of("command", "echo ok", "relativeWorkdir", "src"), () -> false), access());

        assertThat(result.successful()).isTrue();
        assertThat(order).containsExactly("before", "dispatch", "after");
    }

    @Test
    void doesNotDispatchExecutionWhenBaselineIsUnavailable() {
        AtomicBoolean dispatched = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                dispatched.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        ExecutionRepositoryBaselineObserver baselines = new ExecutionRepositoryBaselineObserver() {
            @Override
            public void beforeDispatch(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                throw new RepositoryBaselineUnavailableException("unavailable", new IllegalStateException("git"));
            }

            @Override
            public void afterCompletion(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {
                throw new AssertionError("completion must not run when dispatch never started");
            }
        };

        ToolResult result = operations(broker, 1024, 100, CodingVerificationProfileProvider.empty(), baselines)
                .execute(invocation(Map.of("command", "echo blocked"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("errorCode", "REPOSITORY_BASELINE_UNAVAILABLE");
        assertThat(dispatched).isFalse();
    }

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
                .contains("Command failed (exit 7)", "first", "1 lines omitted", "third")
                .doesNotContain("second");
        assertThat(result.structuredData())
                .containsEntry("toolCallId", "tool-call-1")
                .containsEntry("status", "FAILED")
                .containsEntry("exitCode", 7)
                .containsEntry("truncated", true)
                .containsEntry("outputRef", "stdout-asset")
                .containsEntry("failureCode", "NON_ZERO_EXIT")
                .containsEntry("operationFamily", "TEST")
                .containsEntry("failureCategory", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "NON_ZERO_EXIT")
                .containsEntry("resourceClass", "COMMAND")
                .containsEntry(
                        "scratchSpecDigest",
                        CodingToolchainEnvironmentProfile.defaultScratchSpace().canonicalDigest());
        assertThat(result.structuredData().get("validationEvidence"))
                .isInstanceOfSatisfying(Map.class, evidence -> assertThat(evidence)
                        .containsEntry("status", "FAILED")
                        .containsEntry("scope", "UNKNOWN")
                        .containsEntry("countSource", "COUNTS_UNAVAILABLE")
                        .containsEntry("claimCode", "COMMAND_NOT_IN_FROZEN_PROFILE"));
        assertThat(result.structuredData()).doesNotContainKey("validationAttemptRef");
        assertThat(result.assets()).extracting(AssetRef::assetId).containsExactly("stdout-asset");
    }

    @Test
    void validatesExactFrozenCandidateScopeAcrossDirectAndReconciledResults() {
        AtomicReference<ExecutionResult> completed = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("1 passed, 7 deselected in 0.25s\n"));
                ExecutionResult result = result(request.id(), ExecutionStatus.SUCCEEDED, 0);
                completed.set(result);
                return result;
            }

            @Override
            public Optional<ExecutionResult> findByIdempotencyKey(String idempotencyKey) {
                return Optional.ofNullable(completed.get());
            }
        };

        String command = "python -m pytest focused.py";
        CodingVerificationCandidate candidate = new CodingVerificationCandidate(
                command,
                CodingVerificationCost.LOW,
                Duration.ofMinutes(2),
                CodingVerificationTrigger.ADJACENT_CHANGE,
                CodingVerificationSource.USER_EXPLICIT,
                "trusted-host",
                CodingValidationScope.SELECTED);
        CodingSessionVerificationConfiguration configuration = CodingSessionVerificationConfiguration.freeze(
                new CodingVerificationProfile(List.of(candidate), List.of()));
        ToolInvocationRequest invocation =
                invocation(Map.of("command", command, "operationFamily", "TEST"), () -> false);
        var operations = operations(broker, 4096, 100, ignored -> configuration);
        ToolResult result = operations.execute(invocation, access());
        String expectedValidationAttemptRef = io.haifa.agent.policy.api.PolicyDigest.sha256Fields(List.of(
                "coding-validation-evidence/2",
                "PASSED",
                configuration.digest(),
                configuration.candidateDigest(candidate),
                "TRUSTED_SELECTED_SCOPE"));

        assertThat(result.structuredData().get("validationEvidence"))
                .isInstanceOfSatisfying(Map.class, evidence -> assertThat(evidence)
                        .containsEntry("status", "PASSED")
                        .containsEntry("scope", "SELECTED")
                        .containsEntry("countSource", "COUNTS_UNAVAILABLE")
                        .containsEntry("verificationSource", "USER_EXPLICIT")
                        .containsEntry("claimCode", "TRUSTED_SELECTED_SCOPE")
                        .doesNotContainKeys("discoveredTestCount", "selectedTestCount", "ignoredTestCount"));
        assertThat(result.structuredData()).containsEntry("validationAttemptRef", expectedValidationAttemptRef);
        var validator = new JsonSchema202012Validator();
        assertThat(validator
                        .validate(invocation.binding().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();

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
                                OptionalLong.empty(),
                                PolicyDigest.sha256Fields(
                                        List.of("execution-working-directory-v1", WORKSPACE_ID.value(), ".")))),
                        Optional.empty()),
                access());

        assertThat(reconciled.status()).isEqualTo(ToolReconciliationStatus.RESOLVED);
        assertThat(reconciled.result()).hasValueSatisfying(reconciledResult -> {
            assertThat(reconciledResult.structuredData())
                    .containsEntry("reconcileStatus", "RESOLVED")
                    .containsEntry("replayAllowed", false)
                    .containsEntry("validationAttemptRef", expectedValidationAttemptRef);
            assertThat(validator
                            .validate(
                                    invocation.binding().definition().outputSchema(), reconciledResult.structuredData())
                            .valid())
                    .isTrue();
        });
    }

    @Test
    void treatsTimedOutExecutionWithCompletedWorkspaceObservationAsTerminalWithoutReplaying() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<ExecutionResult> completed = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                executions.incrementAndGet();
                observer.onStarted(new io.haifa.agent.execution.api.ExecutionProcessIdentity(991));
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
                .containsEntry("status", "TIMED_OUT")
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
                    .containsEntry("status", "TIMED_OUT")
                    .containsEntry("reconcileStatus", "RESOLVED")
                    .containsEntry("replayAllowed", false);
        });
    }

    @Test
    void preservesCancellationAndUnknownOutcomeAsDistinctExecutionFacts() {
        AtomicInteger invocation = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
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
                .containsEntry("status", "CANCELLED")
                .containsEntry("failureCategory", "CANCELLED")
                .containsEntry("stableFailureCode", "CANCELLED")
                .containsEntry("failureActionCode", "DO_NOT_AUTOMATICALLY_RETRY")
                .doesNotContainKey("exitCode");
        assertThat(unknown.structuredData())
                .containsEntry("status", "UNKNOWN")
                .containsEntry("runtimeOutcome", "OUTCOME_UNKNOWN")
                .containsEntry("failureCategory", "OUTCOME_UNKNOWN")
                .containsEntry("stableFailureCode", "OUTCOME_UNKNOWN")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY")
                .doesNotContainKey("exitCode");
    }

    @Test
    void exposesConfirmedProcessLimitAsAStableResourceFailure() {
        ExecutionBroker broker = new StubBroker() {
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
                .containsEntry("status", "PROCESS_LIMIT_EXCEEDED")
                .containsEntry("semanticOutcome", "COMMAND_FAILED")
                .containsEntry("semanticReasonCode", "PROCESS_LIMIT_EXCEEDED")
                .containsEntry("failureCategory", "PROCESS_LIMIT")
                .containsEntry("stableFailureCode", "PROCESS_LIMIT_EXCEEDED")
                .containsEntry("observedProcessCount", 1)
                .doesNotContainKey("runtimeOutcome");
    }

    @Test
    void exposesOutputLimitAsAStableExecutionOutcomeWithNarrowingAdvice() {
        ExecutionBroker broker = new StubBroker() {
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
                .containsEntry("status", "OUTPUT_LIMIT_EXCEEDED")
                .containsEntry("failureCategory", "OUTPUT_LIMIT")
                .containsEntry("stableFailureCode", "OUTPUT_LIMIT_EXCEEDED")
                .containsEntry("resourceClass", "OUTPUT")
                .containsEntry("failureActionCode", "VERIFY_OUTCOME_BEFORE_RETRY");
        assertThat(result.structuredData().get("failureAction").toString())
                .contains("narrower fields", "smaller result limit");
    }

    @Test
    void treatsExplicitlyDeclaredNormalNonzeroExitCodesAsSuccessfulToolResults() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
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
                        invocation(
                                Map.of(
                                        "command",
                                        "git diff --exit-code",
                                        "operationFamily",
                                        "DIFF",
                                        "expectedExitCodes",
                                        List.of(0, 1)),
                                () -> false),
                        access());
        var noMatches = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command",
                                        "git grep missing",
                                        "operationFamily",
                                        "INSPECT",
                                        "expectedExitCodes",
                                        List.of(0, 1)),
                                () -> false),
                        access());

        assertThat(differences.successful()).isTrue();
        assertThat(differences.summary())
                .contains("expected result variant", "exit 1", "observedFiles=1", "observedHunks=1")
                .doesNotContain("diff --git", "@@ -1 +1 @@");
        assertThat(differences.structuredData())
                .containsEntry("status", "EXITED")
                .containsEntry("processState", "EXITED")
                .containsEntry("semanticOutcome", "EXPECTED_VARIANT")
                .containsEntry("semanticReasonCode", "DECLARED_EXPECTED_EXIT_CODE")
                .containsEntry("semanticInterpreterVersion", "3")
                .containsEntry("commandOutcomeCode", "COMMAND_EXIT_EXPECTED_VARIANT")
                .containsEntry("expectedExitCodes", List.of(0, 1))
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
                .containsEntry("semanticOutcome", "EXPECTED_VARIANT")
                .containsEntry("semanticReasonCode", "DECLARED_EXPECTED_EXIT_CODE");
    }

    @Test
    void deliversUndeclaredNonzeroExitCodesAsCompletedToolResults() {
        ExecutionBroker broker = new StubBroker() {
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
                .containsEntry("status", "EXITED")
                .containsEntry("processState", "EXITED")
                .containsEntry("exitCode", 1)
                .containsEntry("semanticOutcome", "SUCCEEDED")
                .containsEntry("semanticReasonCode", "COMMAND_EXITED")
                .containsEntry("semanticInterpreterVersion", "3")
                .containsEntry("expectedExitCodes", List.of(0))
                .doesNotContainKeys("failureCategory", "stableFailureCode", "failureCode");
    }

    @Test
    void projectsTrustedDeliveryActionAndVerificationEvidence() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (calls.getAndIncrement() == 0) observer.onOutput(chunk("D:/workspace/project\n"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        ToolResult root = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git rev-parse --show-toplevel"), () -> false), access());
        ToolResult upstream = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command",
                                        "git for-each-ref '--format=%(upstream:short)' refs/heads/feat-delivery"),
                                () -> false),
                        access());
        ToolResult staged = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git add src/Main.java"), () -> false), access());

        assertThat(root.structuredData())
                .containsEntry("deliveryAction", "NONE")
                .containsEntry("deliveryVerification", "REPOSITORY_ROOT")
                .containsEntry("deliveryEvidenceCode", "REPOSITORY_ROOT_VERIFIED")
                .containsKey("deliveryRepositoryScopeDigest")
                .containsKey("deliveryEvidenceRef");
        assertThat(upstream.structuredData())
                .containsEntry("deliveryVerification", "UPSTREAM")
                .containsEntry("deliveryEvidenceCode", "UPSTREAM_INSPECTED")
                .containsKey("deliveryRepositoryScopeDigest");
        assertThat(staged.structuredData())
                .containsEntry("deliveryAction", "STAGE")
                .containsEntry("deliveryVerification", "NONE")
                .containsEntry("deliveryEvidenceCode", "STAGE_COMPLETED")
                .containsKey("deliveryEvidenceRef");
    }

    @Test
    void keepsMissingGitRevisionsAsActionableCommandFailures() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("fatal: bad revision 'missing-ref'\n"));
                return result(request.id(), ExecutionStatus.FAILED, 128);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "git show missing-ref", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("semanticOutcome", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "GIT_REVISION_NOT_FOUND")
                .containsEntry("resourceClass", "REPOSITORY_REF");
        assertThat(result.structuredData().get("failureAction").toString())
                .contains("authoritative repository refs", "retrying once");
    }

    @Test
    void keepsOrdinaryMissingFileOutputAsACommandFailure() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("Traceback: no such file or directory: app.py\n"));
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command", "fast-search needle",
                                        "operationFamily", "INSPECT"),
                                () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "NON_ZERO_EXIT")
                .containsEntry("resourceClass", "COMMAND")
                .containsEntry("failureActionCode", "CONTINUE_WITH_DIAGNOSTIC");
        assertThat(captured.get().limits().maxStdoutBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().maxStderrBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().outputOverflowPolicy())
                .isEqualTo(io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE);
    }

    @Test
    void classifiesOnlyExplicitLauncherEvidenceAsDependencyUnavailable() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("bounded launcher diagnostic\n"));
                return resultWithFailure(
                        request.id(),
                        ExecutionStatus.FAILED,
                        new ExecutionFailure("EXECUTABLE_NOT_FOUND", "configured executable was not found"));
            }
        };

        ToolResult result = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "fast-search needle", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "DEPENDENCY_UNAVAILABLE")
                .containsEntry("stableFailureCode", "EXECUTABLE_NOT_FOUND")
                .containsEntry("resourceClass", "TOOLCHAIN")
                .containsEntry("failureActionCode", "RESTORE_TOOLCHAIN_OR_USE_EQUIVALENT");
    }

    @Test
    void classifiesIsolatedSystemGitAuthenticationAsAnEligibleStableFailure() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("git@github.com: Permission denied (publickey).\n"));
                return result(request.id(), ExecutionStatus.FAILED, 128);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "git ls-remote origin", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "AUTHENTICATION_UNAVAILABLE")
                .containsEntry("stableFailureCode", "GIT_AUTHENTICATION_UNAVAILABLE")
                .containsEntry("resourceClass", "AUTHENTICATION")
                .containsEntry(
                        "failureAction",
                        "Verify the current OS user's Git credential helper or SSH agent, then retry the command.");
    }

    @Test
    void reportsStableGithubCliAvailabilityAndLoginActions() {
        AtomicInteger invocation = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (invocation.getAndIncrement() == 0) {
                    observer.onOutput(chunk("gh: command not found\n"));
                    return resultWithFailure(
                            request.id(),
                            ExecutionStatus.FAILED,
                            new ExecutionFailure("EXECUTABLE_NOT_FOUND", "configured executable was not found"));
                } else {
                    observer.onOutput(chunk("You are not logged into any GitHub hosts.\n"));
                }
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };

        var missing = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "gh pr list --repo owner/repo", "operationFamily", "INSPECT"),
                                () -> false),
                        access());
        var loggedOut = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "gh auth status", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(missing.structuredData())
                .containsEntry("stableFailureCode", "GH_CLI_UNAVAILABLE")
                .containsEntry("failureAction", "Install GitHub CLI and make gh available on the trusted host PATH.");
        assertThat(loggedOut.structuredData())
                .containsEntry("stableFailureCode", "GH_AUTHENTICATION_UNAVAILABLE")
                .containsEntry("failureAction", "Run gh auth login in your system terminal, then retry the command.");
    }

    @Test
    void ignoresOperationHintsForAuthorizationButStillRejectsHardBoundaries() {
        ExecutionBroker broker = new StubBroker() {
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
                .containsEntry("declaredOperationFamily", "INSPECT")
                .containsEntry("effectiveOperationFamily", "MUTATE")
                .containsEntry("operationHintCode", "OPERATION_HINT_IGNORED");
        assertThat(tokenOverride.structuredData())
                .containsEntry("stableFailureCode", "AUTHENTICATION_OVERRIDE_DENIED")
                .containsEntry("failureActionCode", "REMOVE_AUTHENTICATION_OVERRIDE")
                .containsEntry("commandRisk", "DENIED");
        assertThat(statusAsDiff.structuredData())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("declaredOperationFamily", "DIFF")
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("operationHintCode", "OPERATION_HINT_IGNORED")
                .containsEntry("commandOperation", "INSPECT")
                .containsEntry("commandClassificationReason", "GIT_STATUS");
    }

    @Test
    void reportsRiskEscalationAndNetworkPermissionAsActionsInsteadOfParserFailures() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (calls.getAndIncrement() < 2) return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
                observer.onOutput(chunk("fatal: unable to access remote: Could not resolve host\n"));
                return result(request.id(), ExecutionStatus.FAILED, 128);
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
        assertThat(network.structuredData())
                .containsEntry("stableFailureCode", "NETWORK_PERMISSION_REQUIRED")
                .containsEntry("failureActionCode", "REQUEST_EXACT_PERMISSION_ONCE");
    }

    @Test
    void acceptsMissingOperationFamilyAsAnUnknownDeclaredHint() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git status --short"), () -> false), access());

        assertThat(result.structuredData())
                .containsEntry("declaredOperationFamily", "UNKNOWN")
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("operationFamily", "UNKNOWN");
        assertThat(captured.get().limits().maxStdoutBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().maxStderrBytes()).isEqualTo(4096);
    }

    @Test
    void unknownGenericCommandsRemainInsideTheBroadOutputBudget() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
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
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
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
    void reportsGitDirectoryOverrideAsWorkspaceProtocolError() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
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
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
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
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                (access, workspaceRef, relativeWorkdir) -> new WorkspacePath(
                        new WorkspaceId(workspaceRef),
                        relativeWorkdir.equals(".")
                                ? io.haifa.agent.project.path.ProjectPath.root()
                                : io.haifa.agent.project.path.ProjectPath.of(relativeWorkdir)));

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
    void dispatchesGitDeliveryCommandsWithoutAProductSpecificGuard() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                observer.onStarted();
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        ToolResult result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "git push origin feat-delivery", "relativeWorkdir", "."),
                                () -> false),
                        access());

        assertThat(invoked).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData()).containsEntry("deliveryAction", "PUSH");
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
                operations(new StubBroker() {}, 1024, 2000));
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
    void userInitiatedCommandUsesTheSameBrokerWithAProductOwnedOrigin() {
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
                        "terminal-key");

        assertThat(captured.get().context().origin()).isEqualTo(ExecutionOrigin.PRODUCT_USER_COMMAND);
        assertThat(captured.get().context().sourceToolCallId()).isEmpty();
        assertThat(captured.get().context().runRef()).isEqualTo("terminal-audit-1");
        assertThat(captured.get().workingDirectory().projectPath().isRoot()).isTrue();
        assertThat(result.summary()).contains("Command succeeded", "terminal output");
    }

    @Test
    void preservesRealExecutionPathInSummaryAndStructuredOutputByDefault() {
        String realPath = Path.of(System.getProperty("java.io.tmpdir"), "haifa 空格", "workspace", "src", "Main.java")
                .toAbsolutePath()
                .toString();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("built " + realPath + "\n"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 2000)
                .execute(invocation(Map.of("command", "representative build command"), () -> false), access());

        assertThat(result.summary()).contains(realPath).doesNotContain("<workspace>");
        assertThat(result.structuredData().get("output").toString())
                .contains(realPath)
                .doesNotContain("<workspace>");
    }

    @Test
    void supportsAnExplicitGenericOutputSanitizer() {
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

    @Test
    void marksDispatchOnlyAfterTheBrokerReportsProcessStart() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                assertThat(dispatches).hasValue(0);
                assertThat(acknowledgements).hasValue(0);
                observer.onStarted();
                assertThat(dispatches).hasValue(1);
                assertThat(acknowledgements).hasValue(0);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
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
    void mapsPreExecutionObserverFailureAsFailedToolResult() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE",
                        "workspace change observation could not be established before execution",
                        new IllegalStateException("observer failed"));
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "representative command"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData().get("status")).isEqualTo("FAILED");
        assertThat(result.structuredData().get("failureCode")).isEqualTo("WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE");
        assertThat(result.structuredData().get("output").toString())
                .contains("workspace change observation could not be established before execution");
    }

    @Test
    void raisesTrustedNotDispatchedFailureOnlyForEligibleDirectGitPreflight() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "NETWORK_DENIED", "network is unavailable before process dispatch", null);
            }
        };

        assertThatThrownBy(() -> operations(broker, 1024, 2000)
                        .execute(invocation(Map.of("command", "git ls-remote origin"), () -> false), access()))
                .isInstanceOf(io.haifa.agent.tool.api.ToolInvocationException.class)
                .satisfies(failure -> {
                    var invocation = (io.haifa.agent.tool.api.ToolInvocationException) failure;
                    assertThat(invocation.failureCode()).isEqualTo("NETWORK_PERMISSION_REQUIRED");
                    assertThat(invocation.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @Test
    void mapsDirectGitAndGithubAuthenticationPreflightToProductSpecificRecoveryCodes() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "AUTHENTICATION_UNAVAILABLE", "authentication failed before process dispatch", null);
            }
        };
        ProjectExecutionToolOperations operations = operations(broker, 1024, 2000);

        assertThatThrownBy(() ->
                        operations.execute(invocation(Map.of("command", "git fetch origin"), () -> false), access()))
                .isInstanceOf(io.haifa.agent.tool.api.ToolInvocationException.class)
                .satisfies(
                        failure -> assertThat(((io.haifa.agent.tool.api.ToolInvocationException) failure).failureCode())
                                .isEqualTo("GIT_AUTHENTICATION_UNAVAILABLE"));
        assertThatThrownBy(
                        () -> operations.execute(invocation(Map.of("command", "gh repo view"), () -> false), access()))
                .isInstanceOf(io.haifa.agent.tool.api.ToolInvocationException.class)
                .satisfies(
                        failure -> assertThat(((io.haifa.agent.tool.api.ToolInvocationException) failure).failureCode())
                                .isEqualTo("GH_AUTHENTICATION_UNAVAILABLE"));
    }

    @Test
    void leavesOtherTargetPreflightFailureAsOrdinaryUnsuccessfulResult() {
        ExecutionBroker broker = new StubBroker() {
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
        ExecutionBroker broker = new StubBroker() {
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
    void selectsRecoveryProfileOnlyForAppliedExactDeterministicSuccessor() {
        InMemoryRuntimeStore state = new InMemoryRuntimeStore();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        ProjectExecutionToolOperations normal = operations(new StubBroker() {}, 1024, 100);
        ProjectExecutionToolOperations recovery = operations(new StubBroker() {}, 2048, 200);
        var selector = new ProjectExecutionRecoverySelector(
                new ProjectExecutionRecoveryAuthorization(state, interactions),
                normal,
                recovery,
                normalExecutionProfile(),
                executionProfile());
        ToolInvocationRequest originalInvocation =
                invocation(Map.of("command", "git ls-remote origin", "timeoutMillis", 30_000L), () -> false);
        ToolCall source = failedRecoverySource(originalInvocation, "NETWORK_PERMISSION_REQUIRED");
        state.appendToolCall(source);
        ToolApprovalTarget target = recoveryTarget(source);
        applyRecoveryInteraction(interactions, source, target);
        var keys = ExecutionRecoveryKeys.successor(source.runId(), source.id(), target.argumentsDigest());
        ToolInvocationRequest successor =
                invocationWithIdentity(originalInvocation, keys, originalInvocation.arguments());

        assertThatThrownBy(() -> selector.select(successor)).isInstanceOf(SecurityException.class);
        persistRecoverySuccessor(state, source, keys);
        assertThat(selector.select(successor)).isSameAs(recovery);
        assertThat(selector.select(originalInvocation)).isSameAs(normal);

        ToolArguments changed = new ToolArguments(
                originalInvocation.arguments().schemaId(),
                originalInvocation.arguments().schemaVersion(),
                executionArguments(Map.of("command", "git fetch origin", "timeoutMillis", 30_000L)));
        assertThatThrownBy(() -> selector.select(invocationWithIdentity(originalInvocation, keys, changed)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsForgedOrIneligibleRecoveryCorrelation() {
        InMemoryRuntimeStore state = new InMemoryRuntimeStore();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        ProjectExecutionToolOperations normal = operations(new StubBroker() {}, 1024, 100);
        ProjectExecutionToolOperations recovery = operations(new StubBroker() {}, 2048, 200);
        var selector = new ProjectExecutionRecoverySelector(
                new ProjectExecutionRecoveryAuthorization(state, interactions),
                normal,
                recovery,
                normalExecutionProfile(),
                executionProfile());
        ToolInvocationRequest base = invocation(Map.of("command", "git clean -fd"), () -> false);
        ToolCall source = failedRecoverySource(base, "NETWORK_PERMISSION_REQUIRED");
        state.appendToolCall(source);
        ToolApprovalTarget target = recoveryTarget(source);
        applyRecoveryInteraction(interactions, source, target);
        var keys = ExecutionRecoveryKeys.successor(source.runId(), source.id(), target.argumentsDigest());

        assertThatThrownBy(() -> selector.select(invocationWithIdentity(base, keys, base.arguments())))
                .isInstanceOf(SecurityException.class);

        var forged = new ExecutionRecoveryKeys.Successor(
                new ToolCallId("execution-recovery-tool:v1:" + "0".repeat(64)),
                keys.stepId(),
                keys.providerCorrelationId(),
                keys.idempotencyKey());
        assertThatThrownBy(() -> selector.select(invocationWithIdentity(base, forged, base.arguments())))
                .isInstanceOf(SecurityException.class);

        InMemoryRuntimeStore genericState = new InMemoryRuntimeStore();
        InMemoryInteractionPort genericInteractions = new InMemoryInteractionPort();
        var genericSelector = new ProjectExecutionRecoverySelector(
                new ProjectExecutionRecoveryAuthorization(genericState, genericInteractions),
                normal,
                recovery,
                normalExecutionProfile(),
                executionProfile());
        ToolCall genericHostFailure = failedRecoverySource(base, "HOST_AUTHENTICATION_UNAVAILABLE");
        genericState.appendToolCall(genericHostFailure);
        ToolApprovalTarget genericTarget = recoveryTarget(genericHostFailure);
        applyRecoveryInteraction(genericInteractions, genericHostFailure, genericTarget);
        var genericKeys = ExecutionRecoveryKeys.successor(
                genericHostFailure.runId(), genericHostFailure.id(), genericTarget.argumentsDigest());
        assertThatThrownBy(() -> genericSelector.select(invocationWithIdentity(base, genericKeys, base.arguments())))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectedRecoveryInteractionNeverAuthorizesAPersistedSuccessor() {
        InMemoryRuntimeStore state = new InMemoryRuntimeStore();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        var authorization = new ProjectExecutionRecoveryAuthorization(state, interactions);
        ToolInvocationRequest base =
                invocation(Map.of("command", "git ls-remote origin", "timeoutMillis", 30_000L), () -> false);
        ToolCall source = failedRecoverySource(base, "NETWORK_PERMISSION_REQUIRED");
        state.appendToolCall(source);
        ToolApprovalTarget target = recoveryTarget(source);
        applyRecoveryInteraction(interactions, source, target, InteractionResponseType.REJECT);
        var keys = ExecutionRecoveryKeys.successor(source.runId(), source.id(), target.argumentsDigest());
        persistRecoverySuccessor(state, source, keys);

        assertThat(authorization.isVerifiedSuccessor(
                        source.runId(), keys.toolCallId(), keys.idempotencyKey().value(), source.arguments()))
                .isFalse();
    }

    @Test
    void preservesStableSandboxFailureCodeAsFailedToolResult() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new SandboxException("SANDBOX_PROVISION_FAILED", "sandbox setup failed");
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(invocation(Map.of("command", "git status --short"), () -> false), access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData().get("status")).isEqualTo("FAILED");
        assertThat(result.structuredData().get("failureCode")).isEqualTo("SANDBOX_PROVISION_FAILED");
        assertThat(result.structuredData().get("output").toString()).contains("sandbox setup failed");
    }

    @Test
    void handlesTimeoutWithClearDiagnosticMessage() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.TIMED_OUT, null);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(Map.of("command", "go test ./...", "expectedExitCodes", List.of(0, 1)), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData().get("status")).isEqualTo("TIMED_OUT");
        assertThat(result.structuredData().get("semanticOutcome")).isEqualTo("COMMAND_FAILED");
        assertThat(result.structuredData()).containsEntry("expectedExitCodes", List.of(0, 1));
        assertThat(result.summary()).contains("Command timed out");
        assertThat(result.structuredData().get("output").toString()).contains("Command timed out");
    }

    private static ProjectExecutionToolOperations operations(
            ExecutionBroker broker, int maximumOutputBytes, int maximumOutputLines) {
        return operations(broker, maximumOutputBytes, maximumOutputLines, CodingVerificationProfileProvider.empty());
    }

    private static ProjectExecutionToolOperations operations(
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
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                verificationProfiles);
    }

    private static ProjectExecutionToolOperations operations(
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
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                verificationProfiles,
                repositoryBaselines);
    }

    private static ToolInvocationRequest invocation(
            Map<String, Object> arguments, io.haifa.agent.tool.api.ToolCancellation cancellation) {
        return invocation(arguments, cancellation, ToolInvocationObserver.noop());
    }

    private static ToolInvocationRequest invocation(
            Map<String, Object> arguments,
            io.haifa.agent.tool.api.ToolCancellation cancellation,
            ToolInvocationObserver observer) {
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
                List.of(),
                observer);
    }

    private static ToolCall failedRecoverySource(ToolInvocationRequest invocation, String failureCode) {
        ToolCall call = new ToolCall(
                new ToolCallId("source-tool-call"),
                invocation.runId(),
                new AgentStepId("source-step"),
                new ProviderToolCallCorrelationId("source-correlation"),
                new RuntimeIdempotencyKey("source-idempotency"),
                "execution_run",
                invocation.binding().definition().version().value(),
                invocation.arguments(),
                NOW.minusSeconds(2));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.minusSeconds(1));
        call.fail(
                new ToolExecutionError(new AgentError(
                        AgentErrorCode.TOOL_INVOCATION_FAILED,
                        Map.of("failureCode", failureCode, "dispatchState", "NOT_DISPATCHED"),
                        "recovery-source-error",
                        NOW)),
                NOW);
        return call;
    }

    private static ToolApprovalTarget recoveryTarget(ToolCall source) {
        return new ToolApprovalTarget(
                source.id(),
                "haifa-project:execution.run:2.0.0:test",
                "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "tenant-1:user:operator",
                "sha256:" + "3".repeat(64));
    }

    private static void applyRecoveryInteraction(
            InMemoryInteractionPort interactions, ToolCall source, ToolApprovalTarget target) {
        applyRecoveryInteraction(interactions, source, target, InteractionResponseType.APPROVE);
    }

    private static void applyRecoveryInteraction(
            InMemoryInteractionPort interactions,
            ToolCall source,
            ToolApprovalTarget target,
            InteractionResponseType responseType) {
        InteractionRequestId requestId = ExecutionRecoveryKeys.requestId(source.runId(), source.id());
        interactions.create(new InteractionRequest(
                requestId,
                source.runId(),
                new TenantRef("tenant-1"),
                new PrincipalRef("operator", "user"),
                "execution-recovery",
                "Approve one exact retry.",
                true,
                target,
                NOW,
                Optional.empty()));
        interactions.respond(
                new InteractionResponse(
                        new InteractionResponseId("response-1"),
                        requestId,
                        source.runId(),
                        responseType,
                        List.of(),
                        "response-key",
                        NOW),
                new RuntimeCallerContext(new TenantRef("tenant-1"), new PrincipalRef("operator", "user")),
                NOW);
        interactions.markResolutionApplied(requestId);
    }

    private static void persistRecoverySuccessor(
            InMemoryRuntimeStore state, ToolCall source, ExecutionRecoveryKeys.Successor keys) {
        state.appendStep(new AgentStep(
                keys.stepId(), source.runId(), source.stepId(), null, AgentStepType.TOOL_EXECUTION, 1, NOW));
        state.appendToolCall(new ToolCall(
                keys.toolCallId(),
                source.runId(),
                keys.stepId(),
                keys.providerCorrelationId(),
                keys.idempotencyKey(),
                source.toolName(),
                source.toolVersion(),
                source.arguments(),
                NOW));
    }

    private static ToolInvocationRequest invocationWithIdentity(
            ToolInvocationRequest base, ExecutionRecoveryKeys.Successor keys, ToolArguments arguments) {
        return new ToolInvocationRequest(
                base.binding(),
                keys.toolCallId(),
                base.runId(),
                base.tenant(),
                base.principal(),
                arguments,
                base.deadline(),
                Optional.of(keys.idempotencyKey().value()),
                base.cancellation(),
                base.credentialLeases(),
                base.observer());
    }

    private static RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(WORKSPACE_ID, Set.of("execution.run"));
    }

    private static Map<String, Object> executionArguments(Map<String, Object> arguments) {
        var values = new java.util.LinkedHashMap<String, Object>(arguments);
        values.putIfAbsent("workspaceRef", WORKSPACE_ID.value());
        values.putIfAbsent("relativeWorkdir", ".");
        return Map.copyOf(values);
    }

    private static io.haifa.agent.sandbox.api.SandboxProfile executionProfile() {
        return new io.haifa.agent.sandbox.api.SandboxProfile(
                new SandboxProfileRef("shell", "1"),
                "host-guarded",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("test")),
                Set.of(),
                Set.of(),
                true);
    }

    /** A frozen normal profile that is distinct from the recovery profile. */
    private static io.haifa.agent.sandbox.api.SandboxProfile normalExecutionProfile() {
        return new io.haifa.agent.sandbox.api.SandboxProfile(
                new SandboxProfileRef("shell-normal", "1"),
                "host-guarded",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("normal")),
                Set.of(),
                Set.of(),
                true);
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
                "session-1",
                new ResourceUsageSummary(Duration.ofSeconds(1), 1),
                status == ExecutionStatus.FAILED
                        ? new ExecutionFailure("NON_ZERO_EXIT", "process exited with a non-zero code")
                        : null,
                false);
    }

    private static ExecutionResult resultWithoutChangeSet(ExecutionId id, ExecutionStatus status, Integer exitCode) {
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

    private static ExecutionResult resultWithFailure(ExecutionId id, ExecutionStatus status, ExecutionFailure failure) {
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
