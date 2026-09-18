package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ToolCallRow(
        String toolCallId,
        String schemaVersion,
        String runId,
        String stepId,
        String providerCorrelationId,
        String idempotencyKey,
        String toolName,
        String toolVersion,
        String argumentsSchemaVersion,
        byte[] argumentsPayload,
        String argumentsHash,
        String status,
        String resultSchemaVersion,
        byte[] resultPayload,
        String resultHash,
        String errorSchemaVersion,
        byte[] errorPayload,
        String errorHash,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        long version) {}
