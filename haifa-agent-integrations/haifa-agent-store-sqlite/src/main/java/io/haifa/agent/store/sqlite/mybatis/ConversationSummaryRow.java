package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ConversationSummaryRow(
        String summaryId,
        long summaryVersion,
        String sessionId,
        long coveredFrom,
        long coveredThrough,
        String sourceHash,
        String contentSchemaVersion,
        byte[] contentPayload,
        String contentHash,
        int estimatedTokens,
        String policyVersion,
        String compressorVersion,
        boolean valid,
        Instant createdAt) {}
