package io.haifa.agent.execution.core.command;

import io.haifa.agent.execution.api.ExecutionStatus;
import java.util.Objects;

/** Product-neutral status semantics. Product Tools must explicitly declare accepted non-zero exit codes. */
public final class CommandSemanticOutcomeInterpreter {
    public static final String VERSION = "3";

    private CommandSemanticOutcomeInterpreter() {}

    public static Interpretation interpret(String command, ExecutionStatus status, Integer exitCode) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == ExecutionStatus.SUCCEEDED) {
            return new Interpretation(CommandSemanticOutcome.SUCCEEDED, "COMMAND_EXIT_ZERO");
        }
        if (status == ExecutionStatus.PROCESS_LIMIT_EXCEEDED) {
            return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "PROCESS_LIMIT_EXCEEDED");
        }
        if (status == ExecutionStatus.TIMED_OUT) {
            return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "COMMAND_TIMED_OUT");
        }
        if (status == ExecutionStatus.OUTPUT_LIMIT_EXCEEDED) {
            return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "OUTPUT_LIMIT_EXCEEDED");
        }
        if (status == ExecutionStatus.CANCELLED || status == ExecutionStatus.UNKNOWN) {
            return new Interpretation(CommandSemanticOutcome.OUTCOME_UNKNOWN, "EXECUTION_OUTCOME_UNKNOWN");
        }
        if (exitCode == null) {
            return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "COMMAND_NONZERO_EXIT");
        }
        return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "COMMAND_NONZERO_EXIT");
    }

    public record Interpretation(CommandSemanticOutcome outcome, String reasonCode) {
        public Interpretation {
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
                throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
            }
        }

        public boolean successfulToolResult() {
            return outcome == CommandSemanticOutcome.SUCCEEDED
                    || outcome == CommandSemanticOutcome.EXPECTED_VARIANT
                    || outcome == CommandSemanticOutcome.EMPTY_RESULT;
        }
    }
}
