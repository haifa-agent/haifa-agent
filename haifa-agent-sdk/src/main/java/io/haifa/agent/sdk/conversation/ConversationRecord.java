package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;

public record ConversationRecord(
        AgentSessionId sessionId,
        String displayName,
        Instant createdAt,
        Instant lastActivityAt,
        long revision,
        ConversationStatus status) {

    public ConversationRecord {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        displayName = requireText(displayName, "displayName", 256);
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        if (lastActivityAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastActivityAt must not precede createdAt");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        status = Objects.requireNonNull(status, "status must not be null");
    }

    static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
