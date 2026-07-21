package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record OutboxMessage(String id, AgentRunId runId, String type, Map<String, Object> payload, Instant createdAt) {
    public OutboxMessage {
        id = Objects.requireNonNull(id, "id must not be null").trim();
        runId = Objects.requireNonNull(runId, "runId must not be null");
        type = Objects.requireNonNull(type, "type must not be null").trim();
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (id.isEmpty() || type.isEmpty()) throw new IllegalArgumentException("outbox id and type must not be blank");
    }
}
