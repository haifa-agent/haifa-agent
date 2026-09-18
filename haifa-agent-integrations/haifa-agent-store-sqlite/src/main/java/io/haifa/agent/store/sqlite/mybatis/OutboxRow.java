package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record OutboxRow(
        String eventId,
        String runId,
        long sequence,
        String type,
        String payloadSchemaVersion,
        byte[] payload,
        String payloadHash,
        Instant createdAt,
        Instant publishedAt) {}
