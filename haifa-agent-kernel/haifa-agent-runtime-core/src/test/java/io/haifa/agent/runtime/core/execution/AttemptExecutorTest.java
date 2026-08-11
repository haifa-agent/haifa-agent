package io.haifa.agent.runtime.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.core.error.AgentErrorCode;
import org.junit.jupiter.api.Test;

class AttemptExecutorTest {

    @Test
    void classifiesContextBudgetAndWindowFailuresWithoutLeakingMessages() {
        assertThat(AttemptExecutor.classifiedErrorCode(null, failure(ContextBuildFailure.RUN_INPUT_BUDGET_EXHAUSTED)))
                .isEqualTo(AgentErrorCode.RUN_BUDGET_EXCEEDED);
        assertThat(AttemptExecutor.classifiedErrorCode(null, failure(ContextBuildFailure.RUN_OUTPUT_BUDGET_EXHAUSTED)))
                .isEqualTo(AgentErrorCode.RUN_BUDGET_EXCEEDED);
        assertThat(AttemptExecutor.classifiedErrorCode(null, failure(ContextBuildFailure.MODEL_WINDOW_TOO_SMALL)))
                .isEqualTo(AgentErrorCode.MODEL_CONTEXT_TOO_LONG);
        assertThat(AttemptExecutor.classifiedErrorCode(null, failure(ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE)))
                .isEqualTo(AgentErrorCode.MODEL_CONTEXT_TOO_LONG);
        assertThat(AttemptExecutor.classifiedErrorCode(null, failure(ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT)))
                .isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_FAILED);
    }

    private static ContextBuildException failure(ContextBuildFailure failure) {
        return new ContextBuildException(failure, "must not be projected");
    }
}
