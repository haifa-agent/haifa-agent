package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record PolicyDecisionRow(
        String decisionId,
        String snapshotId,
        String tenantId,
        String principalId,
        String principalType,
        String productId,
        String projectRef,
        String sessionRef,
        String runId,
        String attemptId,
        String capability,
        String operation,
        String resourceType,
        String resourceRef,
        String resourceDigest,
        String effect,
        String challenge,
        String reasonCode,
        String safeExplanation,
        String matchedRuleId,
        String matchedRuleVersion,
        String requestDigest,
        String requestSchemaVersion,
        byte[] requestPayload,
        String requestHash,
        Instant decidedAt) {}
