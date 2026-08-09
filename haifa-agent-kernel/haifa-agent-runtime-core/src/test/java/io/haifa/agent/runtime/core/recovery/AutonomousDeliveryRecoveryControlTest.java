package io.haifa.agent.runtime.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.guard.LoopDetectionGuard;
import io.haifa.agent.runtime.core.loop.AgentLoopContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryRecoveryControlTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final String PROFILE_A = "a".repeat(64);
    private static final String PROFILE_B = "b".repeat(64);

    @Test
    void semanticFingerprintIgnoresRandomPathsButSeparatesSandboxProfiles() {
        var classifier = new ToolOutcomeClassifier();
        var first = classifier
                .classify(failed(
                        "call-1",
                        "environment failure at /private/random-a",
                        PROFILE_A,
                        "FILESYSTEM_DENIED",
                        "TEMP_UNWRITABLE"))
                .orElseThrow();
        var second = classifier
                .classify(failed(
                        "call-2",
                        "environment failure at /private/random-b",
                        PROFILE_A,
                        "FILESYSTEM_DENIED",
                        "TEMP_UNWRITABLE"))
                .orElseThrow();
        var otherProfile = classifier
                .classify(failed(
                        "call-3",
                        "environment failure at /private/random-c",
                        PROFILE_B,
                        "FILESYSTEM_DENIED",
                        "TEMP_UNWRITABLE"))
                .orElseThrow();

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.fingerprint()).isNotEqualTo(otherProfile.fingerprint());
        assertThat(first.fingerprint().toString()).doesNotContain("/private", "random-a");
    }

    @Test
    void recoveryEscalatesDeterministicallyAndMeaningfulProgressResetsTheCluster() {
        var controller = new RecoveryController();
        var observation = new ToolOutcomeClassifier()
                .classify(failed("call-1", "bounded failure", PROFILE_A, "DEPENDENCY_UNAVAILABLE", "CACHE_UNAVAILABLE"))
                .orElseThrow();

        assertThat(controller.observe(observation).directive()).isEqualTo(RecoveryDirective.CONTINUE_WITH_DIAGNOSTIC);
        assertThat(controller.observe(observation).directive()).isEqualTo(RecoveryDirective.REQUIRE_STRATEGY_CHANGE);
        assertThat(controller.observe(observation).directive()).isEqualTo(RecoveryDirective.REQUIRE_CONVERGENCE);
        assertThat(controller.observe(observation).directive()).isEqualTo(RecoveryDirective.TERMINATE_REPEATED_FAILURE);

        controller.meaningfulProgress();
        assertThat(controller.observe(observation).directive()).isEqualTo(RecoveryDirective.CONTINUE_WITH_DIAGNOSTIC);
    }

    @Test
    void terminalFailureSummaryReportsCompletedWorkAndManualNextStepWithoutRawArguments() {
        ToolCall compiled = requested(
                "compiled", Map.of("purpose", "生成并编译静态文件服务器", "content", "CANARY_RAW_SCRIPT_MUST_NOT_APPEAR"));
        compiled.beginValidation();
        compiled.beginPolicyCheck();
        compiled.start(NOW.plusSeconds(1));
        compiled.complete(
                new ToolResult(true, "compiled", Map.of("status", "SUCCEEDED"), List.of(), List.of(), false),
                NOW.plusSeconds(2));

        ToolCall blocked =
                requested("blocked", Map.of("purpose", "后台启动服务器", "content", "CANARY_BLOCKED_COMMAND_MUST_NOT_APPEAR"));
        blocked.beginValidation();
        blocked.cancel(NOW.plusSeconds(3));

        AgentStep failedStep = new AgentStep(
                new AgentStepId("failed-step"),
                new AgentRunId("run-1"),
                null,
                null,
                AgentStepType.TOOL_EXECUTION,
                2,
                NOW);
        failedStep.start(NOW.plusSeconds(1));
        failedStep.fail(
                new AgentStepError(new AgentError(
                        AgentErrorCode.TOOL_REQUEST_REJECTED,
                        Map.of(
                                "reason",
                                "ARGUMENTS_INVALID",
                                "repairHint",
                                "Repair the tool arguments: $.content is blocked by the execution safety policy."),
                        "step-diagnostic",
                        NOW.plusSeconds(2))),
                NOW.plusSeconds(2));
        AgentError runError = new AgentError(
                AgentErrorCode.REPEATED_TOOL_FAILURE, Map.of("attempts", 4), "run-diagnostic", NOW.plusSeconds(3));

        String summary = TerminalFailureSummary.create(runError, List.of(compiled, blocked), List.of(failedStep));

        assertThat(summary)
                .contains(
                        "任务未完全完成",
                        "已完成：",
                        "生成并编译静态文件服务器",
                        "未完成：",
                        "后台启动服务器",
                        "工具请求被执行安全策略拒绝",
                        "请在确认安全后手动完成",
                        "REPEATED_TOOL_FAILURE",
                        "run-diagnostic")
                .doesNotContain("CANARY_RAW_SCRIPT", "CANARY_BLOCKED_COMMAND");
    }

    @Test
    void onlyAuthoritativeDeliveryEvidenceCountsAsProgress() {
        var ledger = new ProgressLedger();
        ToolCall failure =
                failed("call-failed", "failure text", PROFILE_A, "DEPENDENCY_UNAVAILABLE", "CACHE_UNAVAILABLE");
        assertThat(ledger.observe(failure)).isFalse();
        assertThat(ledger.size()).isZero();

        ToolCall delivered = completed("call-completed");
        assertThat(ledger.observe(delivered)).isTrue();
        assertThat(ledger.evidence())
                .extracting(ProgressEvidence::type)
                .contains(
                        ProgressEvidence.Type.WORKSPACE_CHANGE,
                        ProgressEvidence.Type.ARTIFACT_CHANGE,
                        ProgressEvidence.Type.VALIDATION_ADVANCE);

        TodoItem todo =
                new TodoItem(new TodoItemId("todo-1"), "verify", "verify delivery", TodoPriority.HIGH, List.of());
        AgentPlan plan =
                new AgentPlan(new AgentPlanId("plan-1"), new AgentRunId("run-1"), "deliver", List.of(todo), NOW);
        assertThat(ledger.observePlan(Optional.of(plan))).isFalse();
        todo.start(Set.of(), NOW.plusSeconds(1));
        assertThat(ledger.observePlan(Optional.of(plan))).isTrue();
        assertThat(ledger.observeInteraction("response-1")).isTrue();
        assertThat(ledger.observeChildResults(1)).isTrue();
        assertThat(ledger.evidence())
                .extracting(ProgressEvidence::type)
                .contains(
                        ProgressEvidence.Type.TODO_ADVANCE,
                        ProgressEvidence.Type.INTERACTION_SUPPLIED,
                        ProgressEvidence.Type.CHILD_RESULT_AVAILABLE);
    }

    @Test
    void distinctSuccessfulValidationCommandsAdvanceProgressWithoutRewardingExactRepeats() {
        var ledger = new ProgressLedger();

        assertThat(ledger.observe(successfulValidation("validation-1", "python -m unittest")))
                .isTrue();
        assertThat(ledger.observe(successfulValidation("validation-2", "python -m unittest")))
                .isFalse();
        assertThat(ledger.observe(successfulValidation("validation-3", "python acceptance.py")))
                .isTrue();

        assertThat(ledger.evidence())
                .extracting(ProgressEvidence::type)
                .containsExactly(ProgressEvidence.Type.VALIDATION_ADVANCE, ProgressEvidence.Type.VALIDATION_ADVANCE);
    }

    @Test
    void outcomeUnknownCancellationAndPolicyDenialKeepTheirSafetyDirectives() {
        var classifier = new ToolOutcomeClassifier();

        assertThat(new RecoveryController()
                        .observe(classifier
                                .classify(failed(
                                        "call-unknown",
                                        "bounded",
                                        PROFILE_A,
                                        "OUTCOME_UNKNOWN",
                                        "TOOL_OUTCOME_UNKNOWN"))
                                .orElseThrow())
                        .directive())
                .isEqualTo(RecoveryDirective.TERMINATE_OUTCOME_UNKNOWN);
        assertThat(new RecoveryController()
                        .observe(classifier
                                .classify(failed("call-cancelled", "bounded", PROFILE_A, "CANCELLED", "TOOL_CANCELLED"))
                                .orElseThrow())
                        .directive())
                .isEqualTo(RecoveryDirective.TERMINATE_CANCELLED);

        ToolCall denied = requested("call-denied");
        denied.beginValidation();
        denied.beginPolicyCheck();
        denied.deny(NOW.plusSeconds(2));
        assertThat(new RecoveryController()
                        .observe(classifier.classify(denied).orElseThrow())
                        .directive())
                .isEqualTo(RecoveryDirective.WAIT_FOR_INTERACTION);
    }

    @Test
    void restoreKeepsFailureClusterAndSuppressesAlreadyCrossedBudgetThresholds() {
        var restored = new AgentLoopContext(3, List.of());
        var snapshot = new RunBudgetSnapshot(4, 4, 4, 4_000, 4, 4, 2, 0, 25);
        restored.rebuildControlState(
                List.of(
                        failed("call-restore-1", "bounded-a", PROFILE_A, "DEPENDENCY_UNAVAILABLE", "CACHE_UNAVAILABLE"),
                        failed(
                                "call-restore-2",
                                "bounded-b",
                                PROFILE_A,
                                "DEPENDENCY_UNAVAILABLE",
                                "CACHE_UNAVAILABLE")),
                Optional.empty(),
                0,
                true,
                snapshot);

        assertThat(restored.failureClusterAttempts()).isEqualTo(2);
        assertThat(restored.controlPrompt())
                .contains("attempts=2", RecoveryDirective.REQUIRE_STRATEGY_CHANGE.name())
                .doesNotContain("bounded-a", "bounded-b");
        assertThat(restored.updateBudgetSnapshot(snapshot)).isEmpty();

        var tenPercent = new RunBudgetSnapshot(1, 1, 1, 1_000, 1, 1, 2, 0, 10);
        assertThat(restored.updateBudgetSnapshot(tenPercent)).containsExactly(10);
        assertThat(restored.updateBudgetSnapshot(tenPercent)).isEmpty();
        assertThat(restored.observeInteractions(List.of("response-after-restore")))
                .isPresent();
        assertThat(restored.failureClusterAttempts()).isZero();
        assertThat(restored.controlPrompt()).doesNotContain("Active failure cluster");
    }

    @Test
    void budgetThresholdsAreIssuedOnceAndProgressHistoryRemainsBounded() {
        var context = new AgentLoopContext(1, List.of());
        assertThat(context.updateBudgetSnapshot(budgetAt(51))).isEmpty();
        assertThat(context.updateBudgetSnapshot(budgetAt(50))).containsExactly(50);
        assertThat(context.updateBudgetSnapshot(budgetAt(50))).isEmpty();
        assertThat(context.updateBudgetSnapshot(budgetAt(25))).containsExactly(25);
        assertThat(context.updateBudgetSnapshot(budgetAt(25))).isEmpty();
        assertThat(context.updateBudgetSnapshot(budgetAt(10))).containsExactly(10);
        assertThat(context.updateBudgetSnapshot(budgetAt(10))).isEmpty();

        var ledger = new ProgressLedger();
        for (int index = 0; index < 40; index++) {
            assertThat(ledger.observeInteraction("response-" + index)).isTrue();
        }
        assertThat(ledger.size()).isEqualTo(ProgressLedger.MAXIMUM_EVIDENCE);
    }

    @Test
    void alternatingDecisionLoopRemainsDetected() {
        var context = new AgentLoopContext(1, List.of("A", "B", "A", "B"));
        assertThatThrownBy(() -> new LoopDetectionGuard(3).check(null, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alternating");
    }

    @Test
    void noProgressWindowStartsAfterFirstAuthoritativeProgress() {
        var initialExploration = new AgentLoopContext(1, List.of("inspect-a", "inspect-b", "inspect-c"));
        initialExploration.recordProgress("no-progress");
        initialExploration.recordProgress("no-progress");
        initialExploration.recordProgress("no-progress");

        assertThatCode(() -> new LoopDetectionGuard(3).check(null, initialExploration))
                .doesNotThrowAnyException();

        var afterDelivery = new AgentLoopContext(1, List.of("inspect-a", "inspect-b", "inspect-c"));
        assertThat(afterDelivery.observeInteractions(List.of("interaction-1"))).isPresent();
        String stableDeliveryState = afterDelivery.progressSignatures().getLast();
        afterDelivery.recordProgress(stableDeliveryState);
        afterDelivery.recordProgress(stableDeliveryState);

        assertThatCode(() -> new LoopDetectionGuard(3).check(null, afterDelivery))
                .doesNotThrowAnyException();

        afterDelivery.recordProgress(stableDeliveryState);

        assertThatThrownBy(() -> new LoopDetectionGuard(3).check(null, afterDelivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no observable progress");
    }

    private static RunBudgetSnapshot budgetAt(int remainingPercent) {
        return new RunBudgetSnapshot(1, 1, 1, 1_000, 1, 1, 0, 0, remainingPercent);
    }

    private static ToolCall failed(
            String id, String message, String sandboxDigest, String category, String stableCode) {
        ToolCall call = requested(id);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.plusSeconds(1));
        call.fail(
                new ToolExecutionError(new AgentError(
                        AgentErrorCode.TOOL_INVOCATION_FAILED,
                        Map.of(
                                "failureCategory",
                                category,
                                "stableFailureCode",
                                stableCode,
                                "resourceClass",
                                "TOOLCHAIN",
                                "operationFamily",
                                "TEST",
                                "sandboxProfileDigest",
                                sandboxDigest),
                        "diag-" + id,
                        NOW.plusSeconds(2))),
                NOW.plusSeconds(2));
        return call;
    }

    private static ToolCall completed(String id) {
        ToolCall call = requested(id);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.plusSeconds(1));
        call.complete(
                new ToolResult(
                        true,
                        "bounded success",
                        Map.of("fileChangeSetId", "change-1", "operationFamily", "TEST", "status", "SUCCEEDED"),
                        List.of(),
                        List.of(new ArtifactRef("artifact-1", "test-report", "1", "Test report")),
                        false),
                NOW.plusSeconds(2));
        return call;
    }

    private static ToolCall successfulValidation(String id, String command) {
        ToolCall call = requested(id, Map.of("operationFamily", "TEST", "command", command, "workdir", "."));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.plusSeconds(1));
        call.complete(
                new ToolResult(
                        true,
                        "bounded success",
                        Map.of("operationFamily", "TEST", "status", "SUCCEEDED"),
                        List.of(),
                        List.of(),
                        false),
                NOW.plusSeconds(2));
        return call;
    }

    private static ToolCall requested(String id) {
        return requested(id, Map.of("operationFamily", "TEST", "command", "omitted"));
    }

    private static ToolCall requested(String id, Map<String, Object> arguments) {
        return new ToolCall(
                new ToolCallId(id),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("key-" + id),
                "execution.run",
                "1.0.0",
                new ToolArguments("execution.input", "1", arguments),
                NOW);
    }
}
