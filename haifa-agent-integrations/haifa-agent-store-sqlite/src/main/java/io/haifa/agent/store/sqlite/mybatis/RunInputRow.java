package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record RunInputRow(
        String inputId,
        String runId,
        Long expectedRunVersion,
        String contentsSchemaVersion,
        byte[] contentsPayload,
        String contentsHash,
        String callerScope,
        String idempotencyKey,
        String canonicalDigest,
        Instant submittedAt,
        Instant acceptedAt,
        String status,
        Instant appliedAt,
        String attemptId,
        Integer iteration,
        String reasonCode) {}
