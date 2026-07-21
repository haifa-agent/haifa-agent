package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.TodoStatus;

public final class TodoConvergenceChecker {
    public boolean isConverged(AgentPlan plan) {
        return plan.items().stream()
                .allMatch(item -> item.status() == TodoStatus.COMPLETED
                        || item.status() == TodoStatus.CANCELLED
                        || item.status() == TodoStatus.SKIPPED);
    }
}
