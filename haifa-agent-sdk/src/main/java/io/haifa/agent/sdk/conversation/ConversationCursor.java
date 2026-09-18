package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;

public record ConversationCursor(Instant lastActivityAt, AgentSessionId sessionId) {
    public ConversationCursor {
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }
}
