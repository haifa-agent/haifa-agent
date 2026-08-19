package io.haifa.agent.execution.core.command;

/** Stable semantic interpretation of a completed command without discarding its raw exit code. */
public enum CommandSemanticOutcome {
    SUCCEEDED,
    EXPECTED_VARIANT,
    EMPTY_RESULT,
    COMMAND_FAILED,
    OUTCOME_UNKNOWN
}
