package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

public record ChangeConversationStatusCommand(AgentSessionId sessionId, long expectedRevision, String idempotencyKey) {
    public ChangeConversationStatusCommand {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        idempotencyKey = ConversationRecord.requireText(idempotencyKey, "idempotencyKey", 256);
    }
}
