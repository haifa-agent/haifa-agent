package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingWorkspaceRegistryRow(
        String projectId,
        String workspaceRef,
        String locationRef,
        String safeDisplayName,
        String source,
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
