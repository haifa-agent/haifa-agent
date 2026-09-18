package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingSessionCommandRow(
        String callerScopeDigest,
        String operation,
        String idempotencyKeyDigest,
        String schemaVersion,
        String requestDigest,
        String dispatchKey,
        String sessionId,
        String projectId,
        byte[] contentNonce,
        byte[] contentCiphertext,
        String contentDigest,
        String runId,
        Instant createdAt) {
    public CodingSessionCommandRow {
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
