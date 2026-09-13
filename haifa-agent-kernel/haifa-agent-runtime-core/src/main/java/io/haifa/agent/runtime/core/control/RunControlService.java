package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.RunTerminationReason;
import java.util.Optional;

/** Converts accepted runtime commands into cooperative executor signals. */
public interface RunControlService {
    Optional<RunControlSignal> currentSignal(AgentRunId runId);

    void requestPause(AgentRun run);

    void requestCancel(AgentRun run);

    void requestCancel(AgentRun run, RunTerminationReason reason);

    void requestTimeout(AgentRun run, RunTerminationReason reason);
}
