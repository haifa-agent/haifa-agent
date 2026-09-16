package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Display and index metadata for SDK conversations. Session, Run, and turn facts remain authoritative in
 * the Runtime; {@code tenant}/{@code principal} are an immutable authorization/list index copied from the
 * trusted caller at creation and are never part of {@link ConversationRecord}. {@code status} on records
 * returned by a store is a placeholder; the service derives it from the Runtime session aggregate.
 */
public interface ConversationStore {
    ConversationRecord create(ConversationRecord conversation, TenantRef tenant, PrincipalRef principal);

    Optional<ConversationRecord> find(AgentSessionId sessionId);

    List<ConversationRecord> list(TenantRef tenant, PrincipalRef principal, ConversationQuery query);

    ConversationRecord touchLastActivity(AgentSessionId sessionId, Instant at);

    ConversationRecord rename(AgentSessionId sessionId, long expectedRevision, String displayName, Instant at);

    ConversationRecord changeStatus(
            AgentSessionId sessionId,
            long expectedRevision,
            ConversationStatus expected,
            ConversationStatus target,
            Instant at);
}
