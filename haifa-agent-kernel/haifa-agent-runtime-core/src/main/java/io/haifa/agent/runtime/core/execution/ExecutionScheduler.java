package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;

@FunctionalInterface
public interface ExecutionScheduler {
    void submit(AgentRunId runId, Runnable task);
}
