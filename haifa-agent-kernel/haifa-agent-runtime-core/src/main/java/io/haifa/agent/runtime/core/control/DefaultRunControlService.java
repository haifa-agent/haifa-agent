package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.Optional;

public final class DefaultRunControlService implements RunControlService {
    private final RunControlRegistry controls;

    public DefaultRunControlService(RunControlRegistry controls) {
        this.controls = Objects.requireNonNull(controls);
    }

    @Override
    public Optional<RunControlSignal> currentSignal(AgentRunId runId) {
        RunControlSignal signal = controls.signal(runId);
        return signal == RunControlSignal.NONE ? Optional.empty() : Optional.of(signal);
    }

    @Override
    public void requestPause(AgentRun run) {
        controls.requestPause(run.id());
    }

    @Override
    public void requestCancel(AgentRun run) {
        controls.requestCancel(run.id());
    }
}
