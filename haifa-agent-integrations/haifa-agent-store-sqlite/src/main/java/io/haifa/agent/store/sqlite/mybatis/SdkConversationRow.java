package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record SdkConversationRow(
        String sessionId,
        String tenantId,
        String principalId,
        String principalType,
        String displayName,
        Instant createdAt,
        Instant lastActivityAt,
        long revision) {}
