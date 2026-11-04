package io.haifa.agent.runtime.core.compaction;

/** Local evaluation allowance, distinct from Run hard limits and invalid summary content. */
final class CompactionBudgetExceededException extends IllegalStateException {
    CompactionBudgetExceededException() {
        super("Compaction physical call allowance exhausted");
    }
}
