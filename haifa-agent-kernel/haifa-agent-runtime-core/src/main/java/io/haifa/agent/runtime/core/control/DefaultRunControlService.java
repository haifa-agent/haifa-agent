package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import java.util.Objects;
import java.util.Optional;

public final class DefaultRunControlService implements RunControlService {
    private final RunControlRegistry controls;
    private final RunTransitionCoordinator transitions;

    public DefaultRunControlService(RunControlRegistry controls, RunTransitionCoordinator transitions) {
        this.controls = Objects.requireNonNull(controls);
        this.transitions = Objects.requireNonNull(transitions);
    }

    @Override
    public Optional<RunControlSignal> currentSignal(AgentRunId runId) {
        RunControlSignal signal = controls.signal(runId);
        return signal == RunControlSignal.NONE ? Optional.empty() : Optional.of(signal);
    }

    @Override
    public void requestPause(AgentRun run) {
        transitions.requestPause(run);
        controls.requestPause(run.id());
    }

    @Override
    public void requestCancel(AgentRun run) {
        controls.requestCancel(run.id());
    }
}
