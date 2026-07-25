package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ToolJournalRow(
        String runId,
        String idempotencyKey,
        String state,
        String toolIdempotency,
        String resultSchemaVersion,
        byte[] resultPayload,
        String resultHash,
        Instant createdAt,
        Instant updatedAt) {}
