package io.haifa.agent.runtime.core.compaction;

/** Local evaluation allowance, distinct from Run hard limits and invalid summary content. */
final class CompactionBudgetExceededException extends IllegalStateException {
    enum Code {
        PHYSICAL_CALL_ALLOWANCE,
        SUMMARY_INPUT_BUDGET
    }

    private final Code code;

    CompactionBudgetExceededException() {
        this(Code.PHYSICAL_CALL_ALLOWANCE);
    }

    CompactionBudgetExceededException(Code code) {
        super("Compaction evaluation budget exhausted: " + code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }
}
