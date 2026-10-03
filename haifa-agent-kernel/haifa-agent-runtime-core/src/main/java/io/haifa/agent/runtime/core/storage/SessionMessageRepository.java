package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

public interface SessionMessageRepository {
    AgentMessage appendSessionMessage(SessionMessageDraft draft);

    List<AgentMessage> messagesAfter(AgentSessionId sessionId, MessageCursor cursor, int limit);

    RecentMessageWindow recentMessages(AgentSessionId sessionId, MessageCursor atOrBefore, int limit);

    Optional<MessageCursor> latestMessageCursor(AgentSessionId sessionId);

    Optional<AgentMessage> message(AgentMessageId id);

    AgentMessage redactMessage(AgentMessageId id);

    /** Bounded read of safe assistant/tool source records, already filtered before applying the limit. */
    default List<AgentMessage> runMessagesAfter(AgentRunId runId, long after, long head, int limit) {
        throw new UnsupportedOperationException("Run message paging is not supported");
    }

    default long runMessageCountThrough(AgentRunId runId, long sequence) {
        throw new UnsupportedOperationException("Run message ranks are not supported");
    }

    default OptionalLong runMessageHead(AgentRunId runId) {
        throw new UnsupportedOperationException("Run message paging is not supported");
    }

    /** Wake-up only, without message bodies; built-in stores defer it until the current commit. */
    default void registerMessageCommitListener(Consumer<AgentRunId> listener) {
        // Existing custom stores that do not support Run message reads remain usable.
    }
}
