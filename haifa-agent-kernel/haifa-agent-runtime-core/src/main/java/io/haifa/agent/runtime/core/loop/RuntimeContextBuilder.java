package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;

@FunctionalInterface
public interface RuntimeContextBuilder {
    RuntimeContextBuildResult build(AgentRun run, AgentLoopContext loopContext, FrozenModelBinding model);
}
