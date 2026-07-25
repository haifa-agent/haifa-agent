package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record InteractionResponseRow(
        String responseId,
        String requestId,
        String runId,
        String responseType,
        String inputsSchemaVersion,
        byte[] inputsPayload,
        String inputsHash,
        String idempotencyKey,
        Instant respondedAt,
        Instant resolvedAt) {}
