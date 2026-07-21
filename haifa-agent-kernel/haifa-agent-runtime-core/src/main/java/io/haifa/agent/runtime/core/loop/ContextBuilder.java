package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface ContextBuilder {
    ContextBuildResult build(AgentRun run, AgentLoopContext loopContext);
}
