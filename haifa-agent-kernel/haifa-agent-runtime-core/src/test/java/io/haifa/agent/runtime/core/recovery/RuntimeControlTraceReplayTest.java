package io.haifa.agent.runtime.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.runtime.core.recovery.RuntimeControlTraceReplay.SafeEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeControlTraceReplayTest {
    private final RuntimeControlTraceReplay replay = new RuntimeControlTraceReplay();

    @Test
    void replaysStructuredTermination() {
        var result = replay.replay(List.of(event("run.structured-termination", "reason", "TERMINATE_OUTCOME_UNKNOWN")));
        assertThat(result.phase()).isEqualTo("FAILED");
        assertThat(result.terminationReason()).isEqualTo("TERMINATE_OUTCOME_UNKNOWN");
    }

    @Test
    void replaysPrematureFinalAsNeutralCompletion() {
        var result = replay.replay(List.of(new SafeEvent(
                "completion.deferred",
                Map.of("attempt", 1, "phase", "COMPLETION", "evidenceCodes", List.of("WORKSPACE_CHANGE")))));
        assertThat(result.phase()).isEqualTo("COMPLETION");
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
        var result = replay.replay(List.of(event("tool.failed", "status", "UNKNOWN")));
        assertThat(result.nonReplayableOutcomeUnknown()).isEqualTo(1);
    }

    @Test
    void checkpointRestorePreservesReducedControlFacts() {
        var result = replay.replay(
                List.of(event("checkpoint.restored"), event("loop.budget-snapshot", "remainingPercent", 25)));
        assertThat(result.checkpointRestored()).isTrue();
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
