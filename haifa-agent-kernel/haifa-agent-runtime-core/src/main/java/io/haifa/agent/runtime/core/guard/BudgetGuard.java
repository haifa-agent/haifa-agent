package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

public final class BudgetGuard implements AgentLoopGuard {
    @Override
    public void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context) {
        if (run.budget().isExceededBy(run.usage())) throw new RuntimeLimitExceededException("run budget exceeded");
        if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
            throw new RuntimeLimitExceededException("model call budget exhausted");
        }
    }
}
