package io.haifa.agent.core.event;

import java.time.Instant;

/** Stable facts emitted by Core aggregates, distinct from traces and audit logs. */
public interface AgentDomainEvent {
    String eventId();

    String eventType();

    String aggregateType();

    String aggregateId();

    long aggregateVersion();

    Instant occurredAt();

    String schemaVersion();
}
