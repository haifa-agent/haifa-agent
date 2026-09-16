package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record AppliedCommandRow(
        String callerScope,
        String operation,
        String idempotencyKey,
        String requestDigest,
        int resultVersion,
        String resultPayload,
        Instant appliedAt) {}
