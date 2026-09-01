package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConversationStore {
    ConversationCommandBinding reserveCommand(ConversationCommandBinding command);

    Optional<ConversationCommandBinding> findCommand(String dispatchKey);

    ConversationCommandBinding completeCommand(String dispatchKey, Optional<AgentRunId> runId, long resultRevision);

    ConversationRecord create(ConversationRecord conversation);

    Optional<ConversationRecord> find(AgentSessionId sessionId);

    List<ConversationRecord> list(TenantRef tenant, PrincipalRef principal, ConversationQuery query);

    ConversationRecord reserveActive(AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant at);

    ConversationRecord activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant at);

    /** Releases a pre-dispatch reservation only when no Run has been bound to it. */
    ConversationRecord releasePendingDispatch(
            AgentSessionId sessionId, String dispatchKey, long expectedRevision, Instant at);

    ConversationRecord clearActive(AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant at);

    ConversationRecord rename(AgentSessionId sessionId, long expectedRevision, String displayName, Instant at);

    ConversationRecord changeStatus(
            AgentSessionId sessionId,
            long expectedRevision,
            ConversationStatus expected,
            ConversationStatus target,
            Instant at);
}
