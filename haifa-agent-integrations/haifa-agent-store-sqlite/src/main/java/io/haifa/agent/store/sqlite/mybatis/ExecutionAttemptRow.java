package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

/** Explicit SQLite row for one physical execution attempt. */
public record ExecutionAttemptRow(
        String attemptId,
        String schemaVersion,
        String runId,
        int attemptNumber,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant completedAt,
        String workerId,
        String resumedFromCheckpointId,
        String errorSchemaVersion,
        byte[] errorPayload,
        String errorHash,
        long version) {}
