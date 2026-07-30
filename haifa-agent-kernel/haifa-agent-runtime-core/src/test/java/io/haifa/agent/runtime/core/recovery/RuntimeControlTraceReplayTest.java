package io.haifa.agent.runtime.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.runtime.core.recovery.RuntimeControlTraceReplay.SafeEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeControlTraceReplayTest {
    private final RuntimeControlTraceReplay replay = new RuntimeControlTraceReplay();

    @Test
    void replaysEnvironmentFailureThenRecoverySuccess() {
        var result = replay.replay(List.of(
                event("tool.failure-cluster-updated", "attempts", 1),
                event("tool.recovery-strategy-required"),
                event("loop.progress-observed"),
                event("run.completed")));
        assertThat(result.phase()).isEqualTo("COMPLETED");
        assertThat(result.maximumFailureClusterAttempts()).isEqualTo(1);
        assertThat(result.meaningfulProgressEvents()).isEqualTo(1);
    }

    @Test
    void replaysRepeatedFailureStrategySwitch() {
        var result = replay.replay(List.of(
                event("tool.failure-cluster-updated", "attempts", 1),
                event("tool.failure-cluster-updated", "attempts", 2),
                event("tool.recovery-strategy-required")));
        assertThat(result.phase()).isEqualTo("RECOVERING");
        assertThat(result.maximumFailureClusterAttempts()).isEqualTo(2);
    }

    @Test
    void replaysStructuredTermination() {
        var result = replay.replay(
                List.of(event("run.structured-termination", "reason", "REPEATED_TOOL_FAILURE_WITHOUT_PROGRESS")));
        assertThat(result.phase()).isEqualTo("FAILED");
        assertThat(result.terminationReason()).isEqualTo("REPEATED_TOOL_FAILURE_WITHOUT_PROGRESS");
    }

    @Test
    void replaysPrematureFinalBackToVerification() {
        var result = replay.replay(List.of(new SafeEvent(
                "completion.deferred",
                Map.of("attempt", 1, "phase", "VERIFYING", "evidenceCodes", List.of("WORKSPACE_CHANGE")))));
        assertThat(result.phase()).isEqualTo("VERIFYING");
        assertThat(result.completionRepairAttempts()).isEqualTo(1);
        assertThat(result.evidenceCodes()).containsExactly("WORKSPACE_CHANGE");
    }

    @Test
    void replaysTwoCompletionRepairsExhausted() {
        var result = replay.replay(List.of(
                event("completion.deferred", "attempt", 1),
                event("completion.deferred", "attempt", 2),
                event("run.structured-termination", "reason", "COMPLETION_REPAIR_EXHAUSTED")));
        assertThat(result.completionRepairAttempts()).isEqualTo(2);
        assertThat(result.terminationReason()).isEqualTo("COMPLETION_REPAIR_EXHAUSTED");
    }

    @Test
    void replaysChangeValidationDiffCompletion() {
        var result = replay.replay(List.of(
                new SafeEvent(
                        "delivery.evidence-updated",
                        Map.of("evidenceCodes", List.of("WORKSPACE_CHANGE", "VALIDATION_PASSED", "DIFF_INSPECTION"))),
                event("run.completed")));
        assertThat(result.evidenceCodes())
                .containsExactlyInAnyOrder("WORKSPACE_CHANGE", "VALIDATION_PASSED", "DIFF_INSPECTION");
        assertThat(result.phase()).isEqualTo("COMPLETED");
    }

    @Test
    void outcomeUnknownIsRecordedAsNonReplayable() {
        var result = replay.replay(List.of(event("execution.failed", "status", "UNKNOWN")));
        assertThat(result.nonReplayableOutcomeUnknown()).isEqualTo(1);
    }

    @Test
    void checkpointRestorePreservesReducedControlFacts() {
        var result = replay.replay(List.of(
                event("tool.failure-cluster-updated", "attempts", 2),
                event("checkpoint.restored"),
                event("loop.budget-snapshot", "remainingPercent", 25)));
        assertThat(result.checkpointRestored()).isTrue();
        assertThat(result.maximumFailureClusterAttempts()).isEqualTo(2);
        assertThat(result.remainingPercent()).isEqualTo(25);
    }

    @Test
    void failedSideEffectAcceptanceRemainsFailed() {
        var result = replay.replay(List.of(event("verification.side-effect-evaluated", "passed", false)));
        assertThat(result.atomicityPassed()).isFalse();
    }

    @Test
    void approvalOrInteractionContinuationIsExplicit() {
        var result = replay.replay(List.of(event("interaction.response-applied"), event("run.completed")));
        assertThat(result.interactionContinued()).isTrue();
        assertThat(result.phase()).isEqualTo("COMPLETED");
    }

    private static SafeEvent event(String type, Object... pairs) {
        var data = new java.util.LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            data.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return new SafeEvent(type, data);
    }
}
