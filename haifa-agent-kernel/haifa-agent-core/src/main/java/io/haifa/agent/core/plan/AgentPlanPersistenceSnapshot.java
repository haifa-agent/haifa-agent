package io.haifa.agent.core.plan;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, versioned persistence contract for controlled {@link AgentPlan} reconstitution. */
public record AgentPlanPersistenceSnapshot(
        String schemaVersion,
        AgentPlanId id,
        AgentRunId runId,
        Instant createdAt,
        String objective,
        List<TodoItemPersistenceSnapshot> items,
        long revision,
        Instant updatedAt) {

    public AgentPlanPersistenceSnapshot {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
