package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

/** Explicit SQLite row for the Core session aggregate. */
public record SessionRow(
        String sessionId,
        String schemaVersion,
        String tenantId,
        String ownerPrincipalId,
        String ownerPrincipalType,
        String projectId,
        String scope,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        long version,
        String metadataSchemaVersion,
        byte[] metadataPayload,
        String metadataHash) {}
