package io.haifa.agent.runtime.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.guard.RuntimeQuotaExceededException;
import org.junit.jupiter.api.Test;

class AttemptExecutorTest {

    @Test
    void classifiesContextWindowAndQuotaFailuresWithoutLeakingMessages() {
        assertThat(AttemptExecutor.classifiedErrorCode(
                        new RuntimeQuotaExceededException("inputTokens", 100, 101), null, null, null))
                .isEqualTo(AgentErrorCode.RUN_INPUT_QUOTA_EXHAUSTED);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        new RuntimeQuotaExceededException("outputTokens", 100, 101), null, null, null))
                .isEqualTo(AgentErrorCode.RUN_OUTPUT_QUOTA_EXHAUSTED);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        new RuntimeQuotaExceededException("costMinorUnits", 100, 101), null, null, null))
                .isEqualTo(AgentErrorCode.RUN_COST_QUOTA_EXHAUSTED);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        null, new RuntimeLimitExceededException("modelCalls", 64, 65), null, null))
                .isEqualTo(AgentErrorCode.RUN_EXECUTION_LIMIT_EXCEEDED);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        null, null, failure(ContextBuildFailure.MODEL_WINDOW_TOO_SMALL), null))
                .isEqualTo(AgentErrorCode.MODEL_CONTEXT_TOO_LONG);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        null, null, failure(ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE), null))
                .isEqualTo(AgentErrorCode.MODEL_CONTEXT_TOO_LONG);
        assertThat(AttemptExecutor.classifiedErrorCode(
                        null, null, failure(ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT), null))
                .isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_FAILED);
    }

    @Test
    void classifiesModelContinuationFailureAsCrossModelContinuationInvalid() {
        var failure = new io.haifa.agent.runtime.core.model.continuation.ModelContinuationException(
                io.haifa.agent.runtime.core.model.continuation.ModelContinuationFailure.BINDING_MISMATCH, "test");

        assertThat(AttemptExecutor.classifiedErrorCode(null, null, null, failure))
                .isEqualTo(AgentErrorCode.CROSS_MODEL_CONTINUATION_INVALID);
    }

    @Test
    void skipsOnlyAttemptsAtomicallyPausedAtEitherWaitingBoundary() {
        assertThat(AttemptExecutor.pausedAtWaitingBoundary(
                        AgentRunStatus.WAITING_APPROVAL, ExecutionAttemptStatus.PAUSED))
                .isTrue();
        assertThat(AttemptExecutor.pausedAtWaitingBoundary(
                        AgentRunStatus.WAITING_INTERACTION, ExecutionAttemptStatus.PAUSED))
                .isTrue();

        assertThat(AttemptExecutor.pausedAtWaitingBoundary(AgentRunStatus.SUSPENDED, ExecutionAttemptStatus.PAUSED))
                .isFalse();
        assertThat(AttemptExecutor.pausedAtWaitingBoundary(
                        AgentRunStatus.WAITING_APPROVAL, ExecutionAttemptStatus.FAILED))
                .isFalse();
    }

    private static ContextBuildException failure(ContextBuildFailure failure) {
        return new ContextBuildException(failure, "must not be projected");
    }
}
