package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.RunTerminationReason;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RunControlRegistry {
    private final ConcurrentHashMap<AgentRunId, RunControlDirective> signals = new ConcurrentHashMap<>();

    public void requestPause(AgentRunId runId) {
        record(runId, new RunControlDirective(RunControlSignal.PAUSE, Optional.empty()));
    }

    public void requestCancel(AgentRunId runId) {
        requestCancel(runId, new RunTerminationReason("USER_CANCELLED", "Cancellation requested by the user"));
    }

    public void requestCancel(AgentRunId runId, RunTerminationReason reason) {
        record(runId, new RunControlDirective(RunControlSignal.CANCEL, Optional.of(reason)));
    }

    public void requestTimeout(AgentRunId runId) {
        requestTimeout(runId, new RunTerminationReason("CONTROL_TIMEOUT", "Runtime timeout signal observed"));
    }

    public void requestTimeout(AgentRunId runId, RunTerminationReason reason) {
        record(runId, new RunControlDirective(RunControlSignal.TIMEOUT, Optional.of(reason)));
    }

    public void reportLeaseLost(AgentRunId runId) {
        record(runId, new RunControlDirective(RunControlSignal.LEASE_LOST, Optional.empty()));
    }

    public void requestAdminStop(AgentRunId runId) {
        record(runId, new RunControlDirective(RunControlSignal.ADMIN_STOP, Optional.empty()));
    }

    public void reportParentCancelled(AgentRunId runId) {
        record(runId, new RunControlDirective(RunControlSignal.PARENT_CANCELLED, Optional.empty()));
    }

    public RunControlSignal signal(AgentRunId runId) {
        return directive(runId).signal();
    }

    public RunControlDirective directive(AgentRunId runId) {
        return signals.getOrDefault(runId, RunControlDirective.NONE);
    }

    public void clear(AgentRunId runId) {
        signals.remove(runId);
    }

    private void record(AgentRunId runId, RunControlDirective signal) {
        signals.merge(runId, signal, RunControlRegistry::stronger);
    }

    private static RunControlDirective stronger(RunControlDirective left, RunControlDirective right) {
        return left.signal().priority() >= right.signal().priority() ? left : right;
    }
}
