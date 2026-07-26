package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record RuntimeEventRow(
        String eventId,
        String runId,
        long sequence,
        String type,
        String eventSchemaVersion,
        String dataSchemaVersion,
        byte[] dataPayload,
        String dataHash,
        Instant occurredAt,
        String correlationId,
        String causationId) {

    public RuntimeEventRow(
            String eventId,
            String runId,
            long sequence,
            String type,
            String dataSchemaVersion,
            byte[] dataPayload,
            String dataHash,
            Instant occurredAt) {
        this(eventId, runId, sequence, type, "1", dataSchemaVersion, dataPayload, dataHash, occurredAt, null, null);
    }
}
