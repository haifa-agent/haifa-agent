package io.haifa.agent.core.step;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;

/** Immutable, versioned persistence contract for controlled {@link AgentStep} reconstitution. */
public record AgentStepPersistenceSnapshot(
        String schemaVersion,
        AgentStepId id,
        AgentRunId runId,
        AgentStepId parentStepId,
        String branchId,
        AgentStepType type,
        int sequence,
        Instant createdAt,
        String status,
        Instant startedAt,
        Instant completedAt,
        AgentStepResult result,
        AgentStepError error,
        long version) {}
