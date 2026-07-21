package io.haifa.agent.context.compression;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;

public interface ConversationSummaryRepository {
    Optional<ConversationSummary> latestValid(AgentSessionId sessionId);

    Optional<ConversationSummary> find(SummaryId id, SummaryVersion version);

    long latestVersion(AgentSessionId sessionId);

    ConversationSummary compareAndSet(ConversationSummary summary, long expectedPreviousVersion);

    void invalidateContaining(AgentSessionId sessionId, AgentMessageId messageId);

    boolean coversValidSource(ConversationSummary summary, MessageCursor through);
}
