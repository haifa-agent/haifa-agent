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

    /**
     * Returns the input already bound to this caller, Run and idempotency key (or input id) without accepting
     * anything. Bound content whose intent differs from the submission is an idempotency conflict.
     */
    Optional<RunInputRecord> findExisting(RunInputSubmission submission, String callerScope);

    Optional<RunInputRecord> find(RunInputId inputId);

    List<RunInputRecord> pending(AgentRunId runId, int limit);

    RunInputRecord markApplied(RunInputId inputId, String attemptId, int iteration, Instant appliedAt);

    /**
     * Settles an accepted input that can no longer reach a safe point because its Run stopped. Rejecting an input
     * that is already rejected returns it unchanged; an applied input cannot be rejected.
     */
    RunInputRecord markRejected(RunInputId inputId, String reasonCode);
}
