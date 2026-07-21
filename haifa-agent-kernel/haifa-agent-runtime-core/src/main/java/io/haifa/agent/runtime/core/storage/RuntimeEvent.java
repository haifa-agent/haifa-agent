package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RuntimeEvent(AgentRunId runId, long sequence, String type, Map<String, Object> data, Instant occurredAt) {
    public RuntimeEvent {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (sequence < 1) throw new IllegalArgumentException("event sequence must be positive");
        type = Objects.requireNonNull(type, "type must not be null").trim();
        if (type.isEmpty()) throw new IllegalArgumentException("type must not be blank");
        data = Map.copyOf(Objects.requireNonNull(data, "data must not be null"));
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
