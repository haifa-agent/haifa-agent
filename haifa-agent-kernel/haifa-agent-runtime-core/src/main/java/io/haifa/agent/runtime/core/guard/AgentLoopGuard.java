package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface AgentLoopGuard {
    void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context);
}
