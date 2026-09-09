package io.haifa.agent.execution.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionStatus;
import org.junit.jupiter.api.Test;

class CommandSemanticOutcomeInterpreterTest {
    @Test
    void treatsExitedProcessOutcomesAsSucceededWithStructuredReasonCodes() {
        assertThat(interpret("git diff --exit-code", ExecutionStatus.EXITED, 0))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(CommandSemanticOutcomeInterpreter.interpret("git diff --exit-code", ExecutionStatus.EXITED, 0)
                        .reasonCode())
                .isEqualTo("COMMAND_EXIT_ZERO");

        assertThat(interpret("git diff --exit-code", ExecutionStatus.EXITED, 1))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(CommandSemanticOutcomeInterpreter.interpret("git diff --exit-code", ExecutionStatus.EXITED, 1)
                        .reasonCode())
                .isEqualTo("COMMAND_EXITED");

        assertThat(interpret("pytest -k test_fail", ExecutionStatus.EXITED, 1))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(interpret("pytest --interrupted", ExecutionStatus.EXITED, 2))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(interpret("pytest --usage-error", ExecutionStatus.EXITED, 4))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);

        assertThat(interpret("rg needle .", ExecutionStatus.EXITED, 1)).isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(interpret("rg needle .", ExecutionStatus.EXITED, 2)).isEqualTo(CommandSemanticOutcome.SUCCEEDED);
    }

    @Test
    void keepsActualInfrastructureFailuresAsCommandFailed() {
        assertThat(interpret("git diff --exit-code", ExecutionStatus.FAILED, null))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("git diff --exit-code", ExecutionStatus.FAILED, 1))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(CommandSemanticOutcomeInterpreter.interpret("git diff --exit-code", ExecutionStatus.FAILED, null)
                        .reasonCode())
                .isEqualTo("EXECUTION_FAILED");
    }

    @Test
    void keepsRealFailuresAndUncertainTerminationDistinct() {
        assertThat(interpret("git diff --exit-code", ExecutionStatus.FAILED, 2))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("git show missing", ExecutionStatus.FAILED, 128))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("git push", ExecutionStatus.UNKNOWN, null))
                .isEqualTo(CommandSemanticOutcome.OUTCOME_UNKNOWN);
        assertThat(interpret("git push", ExecutionStatus.CANCELLED, null))
                .isEqualTo(CommandSemanticOutcome.OUTCOME_UNKNOWN);
        assertThat(interpret("mvn test", ExecutionStatus.TIMED_OUT, null))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(CommandSemanticOutcomeInterpreter.interpret("mvn test", ExecutionStatus.TIMED_OUT, null)
                        .reasonCode())
                .isEqualTo("COMMAND_TIMED_OUT");
        assertThat(interpret("mvn test", ExecutionStatus.PROCESS_LIMIT_EXCEEDED, null))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(CommandSemanticOutcomeInterpreter.interpret("mvn test", ExecutionStatus.PROCESS_LIMIT_EXCEEDED, null)
                        .reasonCode())
                .isEqualTo("PROCESS_LIMIT_EXCEEDED");
    }

    private static CommandSemanticOutcome interpret(String command, ExecutionStatus status, Integer exitCode) {
        return CommandSemanticOutcomeInterpreter.interpret(command, status, exitCode)
                .outcome();
    }
}
