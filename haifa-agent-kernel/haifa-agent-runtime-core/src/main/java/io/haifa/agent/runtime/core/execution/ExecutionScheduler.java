package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;

@FunctionalInterface
public interface ExecutionScheduler {
    void submit(AgentRunId runId, Runnable task);

    /**
     * Submits a continuation that may follow the Run's currently executing task.
     *
     * <p>Schedulers without a process-local single-task lane keep their existing submission semantics. A scheduler
     * that enforces one task per Run may defer this task until the current delegate has completely returned.
     */
    default void submitAfterCurrent(AgentRunId runId, Runnable task) {
        submit(runId, task);
    }

    /** Best-effort interruption of the process-local task currently executing the Run. */
    default void cancel(AgentRunId runId) {}
}
