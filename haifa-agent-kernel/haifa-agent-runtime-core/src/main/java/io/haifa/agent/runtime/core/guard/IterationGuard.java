package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

public final class IterationGuard implements AgentLoopGuard {
    @Override
    public void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context) {
        if (context.iteration() > run.limits().maxIterations())
            throw new IllegalStateException("iteration limit exceeded");
    }
}
