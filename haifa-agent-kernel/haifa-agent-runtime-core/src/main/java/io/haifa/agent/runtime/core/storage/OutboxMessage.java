package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record OutboxMessage(
        String id,
        AgentRunId runId,
        long sequence,
        String type,
        String schemaVersion,
        Map<String, Object> payload,
        Instant createdAt) {

    public static final String CURRENT_SCHEMA_VERSION = "1";

    public OutboxMessage {
        id = Objects.requireNonNull(id, "id must not be null").trim();
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (sequence < 1) throw new IllegalArgumentException("outbox sequence must be positive");
        type = Objects.requireNonNull(type, "type must not be null").trim();
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null")
                .trim();
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (id.isEmpty() || type.isEmpty() || schemaVersion.isEmpty()) {
            throw new IllegalArgumentException("outbox id, type and schemaVersion must not be blank");
        }
    }
}
