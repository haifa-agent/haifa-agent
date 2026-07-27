package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingFollowUpRow(
        String followUpId,
        String schemaVersion,
        String sessionId,
        String boundRunId,
        byte[] contentNonce,
        byte[] contentCiphertext,
        String contentDigest,
        String idempotencyKeyDigest,
        String requestDigest,
        String dispatchKey,
        String status,
        long sequence,
        String dispatchedRunId,
        Instant createdAt,
        Instant updatedAt,
        Instant claimedAt,
        Instant restoredAt,
        long revision) {
    public CodingFollowUpRow {
        contentNonce = contentNonce.clone();
        contentCiphertext = contentCiphertext.clone();
    }

    @Override
    public byte[] contentNonce() {
        return contentNonce.clone();
    }

    @Override
    public byte[] contentCiphertext() {
        return contentCiphertext.clone();
    }
}
