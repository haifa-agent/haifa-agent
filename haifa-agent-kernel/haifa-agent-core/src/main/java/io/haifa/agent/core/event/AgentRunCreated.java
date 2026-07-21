package io.haifa.agent.core.event;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;

/** Fact emitted after a logical run is created. */
public record AgentRunCreated(
        String eventId, AgentRunId runId, long aggregateVersion, Instant occurredAt, String schemaVersion)
        implements AgentDomainEvent {
    public AgentRunCreated {
        eventId = requireText(eventId, "eventId");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
    }

    @Override
    public String eventType() {
        return "agent.run.created";
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
