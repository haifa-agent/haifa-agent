package io.haifa.agent.execution.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionStatus;
import org.junit.jupiter.api.Test;

class CommandSemanticOutcomeInterpreterTest {
    @Test
    void recognizesDocumentedGitExitCodeVariants() {
        assertThat(interpret("git diff --exit-code", ExecutionStatus.SUCCEEDED, 0))
                .isEqualTo(CommandSemanticOutcome.SUCCEEDED);
        assertThat(interpret("git diff --exit-code", ExecutionStatus.FAILED, 1))
                .isEqualTo(CommandSemanticOutcome.EXPECTED_VARIANT);
        assertThat(interpret("git diff --no-index before after", ExecutionStatus.FAILED, 1))
                .isEqualTo(CommandSemanticOutcome.EXPECTED_VARIANT);
        assertThat(interpret("git grep needle", ExecutionStatus.FAILED, 1))
                .isEqualTo(CommandSemanticOutcome.EMPTY_RESULT);
    }

    @Test
    void keepsRealFailuresAndUncertainTerminationDistinct() {
        assertThat(interpret("git diff --exit-code", ExecutionStatus.FAILED, 2))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("git show missing", ExecutionStatus.FAILED, 128))
                .isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("mvn test", ExecutionStatus.FAILED, 1)).isEqualTo(CommandSemanticOutcome.COMMAND_FAILED);
        assertThat(interpret("git push", ExecutionStatus.TIMED_OUT, null))
                .isEqualTo(CommandSemanticOutcome.OUTCOME_UNKNOWN);
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
