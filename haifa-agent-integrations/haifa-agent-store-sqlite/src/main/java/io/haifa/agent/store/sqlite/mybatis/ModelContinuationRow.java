package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ModelContinuationRow(
        String continuationId,
        String continuationVersion,
        String continuationDigest,
        int byteLength,
        String assistantMessageId,
        String runId,
        String sessionId,
        String modelCallId,
        String providerId,
        String modelId,
        String configurationDigest,
        String toolCorrelationsSchemaVersion,
        byte[] toolCorrelationsPayload,
        String toolCorrelationsHash,
        String protectionVersion,
        String nonceSchemaVersion,
        byte[] noncePayload,
        String nonceHash,
        String ciphertextSchemaVersion,
        byte[] ciphertextPayload,
        String ciphertextHash,
        Instant createdAt) {}
