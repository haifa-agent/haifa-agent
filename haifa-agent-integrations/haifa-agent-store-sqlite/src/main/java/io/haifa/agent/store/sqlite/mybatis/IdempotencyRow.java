package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record IdempotencyRow(
        String callerScope,
        String operation,
        String idempotencyKey,
        String runId,
        boolean commandApplied,
        String resultSchemaVersion,
        byte[] resultPayload,
        String resultHash,
        Instant createdAt,
        Instant updatedAt) {}
