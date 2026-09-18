package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record SessionMessageRow(
        String messageId,
        String sessionId,
        String runId,
        String parentMessageId,
        long sequence,
        String role,
        String status,
        String visibility,
        String contentSchemaVersion,
        byte[] contentPayload,
        String contentHash,
        String metadataSchemaVersion,
        byte[] metadataPayload,
        String metadataHash,
        Instant createdAt) {}
