package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingAuthorizedDirectoryRow(
        String projectId,
        String workspaceRef,
        String tenantId,
        String principalType,
        String principalId,
        String mode,
        String safeDisplayName,
        String status,
        byte[] locationNonce,
        byte[] locationCiphertext,
        String locationDigest,
        String physicalFingerprint,
        Instant createdAt,
        Instant validatedAt,
        Instant revokedAt,
        String revocationReasonCode,
        long version) {}
