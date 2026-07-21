package io.haifa.agent.core.event;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import java.time.Instant;
import java.util.Objects;

/** Fact emitted after a valid run lifecycle transition. */
public record AgentRunStatusChanged(
        String eventId,
        AgentRunId runId,
        AgentRunStatus previousStatus,
        AgentRunStatus currentStatus,
        long aggregateVersion,
        Instant occurredAt,
        String schemaVersion)
        implements AgentDomainEvent {
    public AgentRunStatusChanged {
        eventId = requireText(eventId, "eventId");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        previousStatus = Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        currentStatus = Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        if (previousStatus == currentStatus) {
            throw new IllegalArgumentException("status change must contain different states");
        }
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("status change aggregateVersion must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
    }

    @Override
    public String eventType() {
        return "agent.run.status-changed";
    }

    @Override
    public String aggregateType() {
        return "AgentRun";
    }

    @Override
    public String aggregateId() {
        return runId.value();
    }
}
