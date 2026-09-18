package io.haifa.agent.runtime.core.attempt;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;

/** Immutable, versioned persistence contract for controlled attempt reconstitution. */
public record ExecutionAttemptPersistenceSnapshot(
        String schemaVersion,
        ExecutionAttemptId attemptId,
        AgentRunId runId,
        int attemptNumber,
        Instant createdAt,
        CheckpointId resumedFromCheckpointId,
        String status,
        Instant startedAt,
        Instant heartbeatAt,
        Instant completedAt,
        String workerId,
        AgentError error,
        long version) {}
