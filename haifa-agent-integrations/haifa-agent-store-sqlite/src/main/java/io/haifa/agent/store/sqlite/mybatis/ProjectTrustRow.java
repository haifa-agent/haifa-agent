package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record ProjectTrustRow(
        String trustId,
        String tenantId,
        String principalId,
        String principalType,
        String projectRef,
        String canonicalProjectIdentity,
        String trustedRootIdentity,
        String authorizationConfigurationDigest,
        String productProfileRef,
        String state,
        Instant confirmedAt,
        Instant expiresAt,
        Instant revokedAt,
        String revocationReasonCode,
        long version) {}
