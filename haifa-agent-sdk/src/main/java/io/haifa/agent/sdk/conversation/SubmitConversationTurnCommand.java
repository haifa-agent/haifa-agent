package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

public record SubmitConversationTurnCommand(
        AgentSessionId sessionId, long expectedRevision, String idempotencyKey, String message) {
    public SubmitConversationTurnCommand {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 256);
        message = requireText(message, "message", 32_000);
    }

    private static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
