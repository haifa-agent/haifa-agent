package io.haifa.agent.runtime.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.runtime.core.guard.LoopDetectedException;
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
                .isEqualTo(AgentErrorCode.RUN_BUDGET_EXCEEDED);
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
    void classifiesLoopDetectionAsAStableRuntimeFailure() {
        var failure = new LoopDetectedException(LoopDetectedException.Reason.NO_OBSERVABLE_PROGRESS);

        assertThat(AttemptExecutor.classifiedErrorCode(null, null, null, failure))
                .isEqualTo(AgentErrorCode.AGENT_LOOP_DETECTED);
    }

    private static ContextBuildException failure(ContextBuildFailure failure) {
        return new ContextBuildException(failure, "must not be projected");
    }
}
