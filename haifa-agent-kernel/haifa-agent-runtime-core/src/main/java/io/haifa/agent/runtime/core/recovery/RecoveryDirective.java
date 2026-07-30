package io.haifa.agent.runtime.core.recovery;

public enum RecoveryDirective {
    CONTINUE_WITH_DIAGNOSTIC,
    REQUIRE_STRATEGY_CHANGE,
    REQUIRE_CONVERGENCE,
    WAIT_FOR_INTERACTION,
    TERMINATE_REPEATED_FAILURE,
    TERMINATE_OUTCOME_UNKNOWN,
    TERMINATE_CANCELLED;

    public String guidance() {
        return switch (this) {
            case CONTINUE_WITH_DIAGNOSTIC ->
                "Diagnose the structured failure before another attempt; do not vary random host paths.";
            case REQUIRE_STRATEGY_CHANGE ->
                "The same semantic failure repeated without progress. Change strategy before another attempt.";
            case REQUIRE_CONVERGENCE ->
                "Converge now: make one evidence-backed delivery attempt or return a structured blocker.";
            case WAIT_FOR_INTERACTION ->
                "Policy denied this operation. Do not retry unchanged; request interaction or choose an allowed path.";
            case TERMINATE_REPEATED_FAILURE -> "Repeated semantic failure reached the no-progress limit.";
            case TERMINATE_OUTCOME_UNKNOWN -> "Tool outcome is unknown. Automatic replay is forbidden.";
            case TERMINATE_CANCELLED -> "Cancellation is terminal for the current execution path.";
        };
    }

    public boolean terminal() {
        return this == TERMINATE_REPEATED_FAILURE || this == TERMINATE_OUTCOME_UNKNOWN || this == TERMINATE_CANCELLED;
    }
}
