package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRunId;
import java.util.concurrent.ConcurrentHashMap;

public final class RunControlRegistry {
    private final ConcurrentHashMap<AgentRunId, RunControlSignal> signals = new ConcurrentHashMap<>();

    public void requestPause(AgentRunId runId) {
        record(runId, RunControlSignal.PAUSE);
    }

    public void requestCancel(AgentRunId runId) {
        record(runId, RunControlSignal.CANCEL);
    }

    public void requestTimeout(AgentRunId runId) {
        record(runId, RunControlSignal.TIMEOUT);
    }

    public void reportLeaseLost(AgentRunId runId) {
        record(runId, RunControlSignal.LEASE_LOST);
    }

    public void requestAdminStop(AgentRunId runId) {
        record(runId, RunControlSignal.ADMIN_STOP);
    }

    public void reportParentCancelled(AgentRunId runId) {
        record(runId, RunControlSignal.PARENT_CANCELLED);
    }

    public RunControlSignal signal(AgentRunId runId) {
        return signals.getOrDefault(runId, RunControlSignal.NONE);
    }

    public void clear(AgentRunId runId) {
        signals.remove(runId);
    }

    private void record(AgentRunId runId, RunControlSignal signal) {
        signals.merge(runId, signal, RunControlRegistry::stronger);
    }

    private static RunControlSignal stronger(RunControlSignal left, RunControlSignal right) {
        return left.priority() >= right.priority() ? left : right;
    }
}
