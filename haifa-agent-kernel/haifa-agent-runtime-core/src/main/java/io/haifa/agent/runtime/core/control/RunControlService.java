package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Optional;

/** Converts accepted runtime commands into cooperative executor signals. */
public interface RunControlService {
    Optional<RunControlSignal> currentSignal(AgentRunId runId);

    void requestPause(AgentRun run);

    void requestCancel(AgentRun run);
}
