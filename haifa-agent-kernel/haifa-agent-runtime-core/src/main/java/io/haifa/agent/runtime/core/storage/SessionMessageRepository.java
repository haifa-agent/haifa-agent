package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository {
    AgentMessage appendSessionMessage(SessionMessageDraft draft);

    List<AgentMessage> messagesAfter(AgentSessionId sessionId, MessageCursor cursor, int limit);

    RecentMessageWindow recentMessages(AgentSessionId sessionId, MessageCursor atOrBefore, int limit);

    Optional<MessageCursor> latestMessageCursor(AgentSessionId sessionId);

    Optional<AgentMessage> message(AgentMessageId id);

    AgentMessage redactMessage(AgentMessageId id);
}
