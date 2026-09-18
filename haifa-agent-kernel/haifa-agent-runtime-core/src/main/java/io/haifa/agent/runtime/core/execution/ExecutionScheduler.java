package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;

@FunctionalInterface
public interface ExecutionScheduler {
    void submit(AgentRunId runId, Runnable task);

    /** Best-effort interruption of the process-local task currently executing the Run. */
    default void cancel(AgentRunId runId) {}
}
