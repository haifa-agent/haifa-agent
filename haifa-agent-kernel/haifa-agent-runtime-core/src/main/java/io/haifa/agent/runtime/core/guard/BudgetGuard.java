package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

public final class BudgetGuard implements AgentLoopGuard {
    @Override
    public void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context) {
        if (run.budget().isExceededBy(run.usage())) throw new RuntimeLimitExceededException("run budget exceeded");
        if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
            throw new RuntimeLimitExceededException("model call budget exhausted");
        }
        if (near(run.usage().modelCalls(), run.budget().maxModelCalls())
                || near(run.usage().toolCalls(), run.budget().maxToolCalls())
                || near(run.usage().childRuns(), run.budget().maxChildRuns())
                || near(run.usage().inputTokens(), run.budget().maxInputTokens())
                || near(run.usage().outputTokens(), run.budget().maxOutputTokens())
                || near(run.usage().costMinorUnits(), run.budget().maxCostMinorUnits())) {
            context.requestConvergence("resource budget is nearing its hard limit; finish with a valid result");
        }
    }

    private static boolean near(long used, long maximum) {
        return maximum > 0 && used * 10L >= maximum * 8L;
    }
}
