package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record InteractionRequestRow(
        String requestId,
        String runId,
        String tenantId,
        String principalId,
        String principalType,
        String type,
        String prompt,
        boolean approval,
        String targetType,
        String targetSchemaVersion,
        byte[] targetPayload,
        String targetHash,
        Instant createdAt,
        Instant expiresAt) {}
