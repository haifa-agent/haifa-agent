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
        String dispatchExecutionId,
        Long dispatchProcessId,
        String dispatchWorkdirDigest,
        String reconcileStatus,
        String reconcileReason,
        Instant createdAt,
        Instant updatedAt) {}
