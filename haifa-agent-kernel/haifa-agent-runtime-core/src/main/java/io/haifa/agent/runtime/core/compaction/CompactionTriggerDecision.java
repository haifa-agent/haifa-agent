package io.haifa.agent.runtime.core.compaction;

import java.util.Objects;

/**
 * Result of evaluating whether conversation compaction should run.
 */
public record CompactionTriggerDecision(
        boolean shouldCompact,
        CompactionTriggerReason reason,
        ContextBudgetBreakdown budgetBreakdown) {

    public CompactionTriggerDecision {
        reason = Objects.requireNonNull(reason, "reason must not be null");
        budgetBreakdown = Objects.requireNonNull(budgetBreakdown, "budgetBreakdown must not be null");
    }

    public static CompactionTriggerDecision doNotCompact(ContextBudgetBreakdown budgetBreakdown) {
        return new CompactionTriggerDecision(false, CompactionTriggerReason.NONE, budgetBreakdown);
    }

    public static CompactionTriggerDecision compact(CompactionTriggerReason reason, ContextBudgetBreakdown budgetBreakdown) {
        return new CompactionTriggerDecision(true, reason, budgetBreakdown);
    }
}
