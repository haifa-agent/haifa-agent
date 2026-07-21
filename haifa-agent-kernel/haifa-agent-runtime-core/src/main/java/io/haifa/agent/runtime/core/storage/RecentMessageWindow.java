package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

public record RecentMessageWindow(
        AgentSessionId sessionId, MessageCursor from, MessageCursor through, List<AgentMessage> messages) {
    public RecentMessageWindow {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        from = Objects.requireNonNull(from, "from must not be null");
        through = Objects.requireNonNull(through, "through must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()
                && (!from.equals(MessageCursor.BEFORE_FIRST) || !through.equals(MessageCursor.BEFORE_FIRST))) {
            throw new IllegalArgumentException("empty window must use the before-first cursor");
        }
    }
}
