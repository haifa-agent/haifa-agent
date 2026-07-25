package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record StepRow(
        String stepId,
        String schemaVersion,
        String runId,
        String parentStepId,
        String branchId,
        String type,
        int sequence,
        String status,
        String resultSchemaVersion,
        byte[] resultPayload,
        String resultHash,
        String errorSchemaVersion,
        byte[] errorPayload,
        String errorHash,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long version) {}
