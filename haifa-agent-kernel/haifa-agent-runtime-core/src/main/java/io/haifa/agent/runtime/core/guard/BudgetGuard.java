package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.QuotaMode;

public final class BudgetGuard implements AgentLoopGuard {
    @Override
    public void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context) {
        if (run.usage().modelCalls() >= run.limits().maxModelCalls()) {
            throw new RuntimeLimitExceededException(
                    "modelCalls", run.limits().maxModelCalls(), run.usage().modelCalls());
        }
        if (run.usage().toolCalls() > run.limits().maxToolCalls()) {
            throw new RuntimeLimitExceededException(
                    "toolCalls", run.limits().maxToolCalls(), run.usage().toolCalls());
        }
        if (run.usage().childRuns() > run.limits().maxChildRuns()) {
            throw new RuntimeLimitExceededException(
                    "childRuns", run.limits().maxChildRuns(), run.usage().childRuns());
        }
        if (run.quotaPolicy().mode() == QuotaMode.HARD_STOP && run.quotaPolicy().isExceededBy(run.usage())) {
            throw RuntimeQuotaExceededException.forRunQuota(run);
        }
    }
}
