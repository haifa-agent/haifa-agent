package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record SdkConversationCommandRow(
        String dispatchKey,
        String callerScopeDigest,
        String operation,
        String idempotencyKeyDigest,
        String requestDigest,
        String sessionId,
        String runId,
        boolean completed,
        Long resultRevision,
        Instant createdAt) {}
