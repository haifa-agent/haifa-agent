package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface ModelSelector {
    ModelClient select(AgentRun run);
}
