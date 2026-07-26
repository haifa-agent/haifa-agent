package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record PolicyAuthorizationEvidenceRow(
        String decisionId,
        String requestDigest,
        String requesterTenantId,
        String requesterPrincipalId,
        String requesterPrincipalType,
        String responderTenantId,
        String responderPrincipalId,
        String responderPrincipalType,
        Instant approvedAt,
        Instant expiresAt) {}
