package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;
import java.util.ArrayDeque;
import java.util.Queue;

/** Deterministic scheduler for tests and embedded callers that own execution pumping. */
public final class ManualExecutionScheduler implements ExecutionScheduler {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void submit(AgentRunId runId, Runnable task) {
        tasks.add(task);
    }

    public void runNext() {
        Runnable task;
        synchronized (this) {
            task = tasks.remove();
        }
        task.run();
    }

    public void runAll() {
        while (pending() > 0) runNext();
    }

    public synchronized int pending() {
        return tasks.size();
    }
}
