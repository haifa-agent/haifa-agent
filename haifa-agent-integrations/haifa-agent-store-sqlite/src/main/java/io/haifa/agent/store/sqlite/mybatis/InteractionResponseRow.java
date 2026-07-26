package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record InteractionResponseRow(
        String responseId,
        String requestId,
        String runId,
        String responseType,
        String inputsSchemaVersion,
        byte[] inputsPayload,
        String inputsHash,
        String idempotencyKey,
        Instant respondedAt,
        Instant resolvedAt,
        String action,
        long expectedRevision,
        String callerScope,
        String canonicalDigest,
        String responderTenantId,
        String responderPrincipalId,
        String responderPrincipalType,
        String receiptStatus) {

    public InteractionResponseRow(
            String responseId,
            String requestId,
            String runId,
            String responseType,
            String inputsSchemaVersion,
            byte[] inputsPayload,
            String inputsHash,
            String idempotencyKey,
            Instant respondedAt,
            Instant resolvedAt) {
        this(
                responseId,
                requestId,
                runId,
                responseType,
                inputsSchemaVersion,
                inputsPayload,
                inputsHash,
                idempotencyKey,
                respondedAt,
                resolvedAt,
                switch (responseType) {
                    case "APPROVE" -> "approve";
                    case "REJECT" -> "reject";
                    default -> "submit";
                },
                0L,
                "",
                "",
                "",
                "",
                "",
                "ACCEPTED");
    }
}
