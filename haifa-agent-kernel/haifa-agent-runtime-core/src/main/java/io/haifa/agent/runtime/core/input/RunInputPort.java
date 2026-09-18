package io.haifa.agent.runtime.core.input;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputSubmission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for accepted steer input. SQLite implements it in Task 02. */
public interface RunInputPort {
    RunInputAcceptance accept(RunInputSubmission submission, String callerScope, Instant acceptedAt);

    Optional<RunInputRecord> find(RunInputId inputId);

    List<RunInputRecord> pending(AgentRunId runId, int limit);

    RunInputRecord markApplied(RunInputId inputId, String attemptId, int iteration, Instant appliedAt);
}
