package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record RuntimeEventRow(
        String eventId,
        String runId,
        long sequence,
        String type,
        String dataSchemaVersion,
        byte[] dataPayload,
        String dataHash,
        Instant occurredAt) {}
