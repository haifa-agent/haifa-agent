package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Optional;

/** Product-neutral multi-run conversation boundary. Deletion is intentionally absent from version 1. */
public interface ConversationService {
    ConversationRecord start(StartConversationCommand command);

    Optional<ConversationRecord> find(AgentSessionId sessionId);

    ConversationPage list(ConversationQuery query);

    ConversationTurnPage turns(AgentSessionId sessionId, ConversationTurnQuery query);

    default List<ConversationTurn> turns(AgentSessionId sessionId) {
        return turns(sessionId, ConversationTurnQuery.first(100)).items();
    }

    ConversationRecord submit(SubmitConversationTurnCommand command);

    ConversationRecord rename(RenameConversationCommand command);

    ConversationRecord archive(ChangeConversationStatusCommand command);

    ConversationRecord unarchive(ChangeConversationStatusCommand command);
}
