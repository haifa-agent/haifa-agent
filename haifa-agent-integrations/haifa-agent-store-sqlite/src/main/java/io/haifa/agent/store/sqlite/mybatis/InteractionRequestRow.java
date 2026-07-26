package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record InteractionRequestRow(
        String requestId,
        String runId,
        String tenantId,
        String principalId,
        String principalType,
        String type,
        String prompt,
        boolean approval,
        String targetType,
        String targetSchemaVersion,
        byte[] targetPayload,
        String targetHash,
        Instant createdAt,
        Instant expiresAt,
        long revision,
        String kind,
        String state,
        String expirationOutcome,
        String stateReasonCode,
        Instant stateChangedAt) {

    public InteractionRequestRow(
            String requestId,
            String runId,
            String tenantId,
            String principalId,
            String principalType,
            String type,
            String prompt,
            boolean approval,
            String targetType,
            String targetSchemaVersion,
            byte[] targetPayload,
            String targetHash,
            Instant createdAt,
            Instant expiresAt) {
        this(
                requestId,
                runId,
                tenantId,
                principalId,
                principalType,
                type,
                prompt,
                approval,
                targetType,
                targetSchemaVersion,
                targetPayload,
                targetHash,
                createdAt,
                expiresAt,
                0L,
                approval ? "approval" : type,
                "PENDING",
                approval ? "CANCEL_RUN" : "FAIL_RUN",
                null,
                null);
    }
}
