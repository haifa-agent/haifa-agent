package io.haifa.agent.store.sqlite.mybatis;

public record ApprovalRequestMetadataRow(
        String requestId,
        String decisionId,
        String semantics,
        String challenge,
        String requesterTenantId,
        String requesterPrincipalId,
        String requesterPrincipalType,
        String targetType,
        String targetId,
        String targetVersion,
        String targetOperation,
        String targetDigest,
        String targetSafeSummary,
        String authorityProviderId,
        String authorityRequirementId,
        String authorityRequirementVersion,
        String externalCorrelationRef,
        String metadataSchemaVersion,
        byte[] metadataPayload,
        String metadataHash) {}
