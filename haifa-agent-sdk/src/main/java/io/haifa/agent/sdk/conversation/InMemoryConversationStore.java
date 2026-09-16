package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryConversationStore implements ConversationStore {
    private final Map<AgentSessionId, Indexed> conversations = new LinkedHashMap<>();

    @Override
    public synchronized ConversationRecord create(
            ConversationRecord conversation, TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Indexed existing =
                conversations.putIfAbsent(conversation.sessionId(), new Indexed(tenant, principal, conversation));
        if (existing != null) {
            if (!existing.tenant().equals(tenant) || !existing.principal().equals(principal)) {
                throw conflict("CONVERSATION_SCOPE_CONFLICT");
            }
            return existing.record();
        }
        return conversation;
    }

    @Override
    public synchronized Optional<ConversationRecord> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Indexed value = conversations.get(sessionId);
        return value == null ? Optional.empty() : Optional.of(value.record());
    }

    @Override
    public synchronized List<ConversationRecord> list(
            TenantRef tenant, PrincipalRef principal, ConversationQuery query) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Comparator<ConversationRecord> order = Comparator.comparing(ConversationRecord::lastActivityAt)
                .reversed()
                .thenComparing(value -> value.sessionId().value(), Comparator.reverseOrder());
        return conversations.values().stream()
                .filter(value ->
                        value.tenant().equals(tenant) && value.principal().equals(principal))
                .map(Indexed::record)
                .filter(value -> query.text()
                        .map(text -> value.displayName()
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains(text.toLowerCase(java.util.Locale.ROOT)))
                        .orElse(true))
                .filter(value -> query.after()
                        .map(cursor -> value.lastActivityAt().isBefore(cursor.lastActivityAt())
                                || (value.lastActivityAt().equals(cursor.lastActivityAt())
                                        && value.sessionId()
                                                        .value()
                                                        .compareTo(cursor.sessionId()
                                                                .value())
                                                < 0))
                        .orElse(true))
                .sorted(order)
                .limit(query.limit() + 1L)
                .toList();
    }

    @Override
    public synchronized ConversationRecord touchLastActivity(AgentSessionId sessionId, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        ConversationRecord current = requireConversation(sessionId);
        return save(new ConversationRecord(
                current.sessionId(),
                current.displayName(),
                current.createdAt(),
                at,
                current.revision() + 1,
                current.status()));
    }

    @Override
    public synchronized ConversationRecord rename(
            AgentSessionId sessionId, long expectedRevision, String displayName, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        return save(new ConversationRecord(
                current.sessionId(), displayName, current.createdAt(), at, current.revision() + 1, current.status()));
    }

    @Override
    public synchronized ConversationRecord changeStatus(
            AgentSessionId sessionId,
            long expectedRevision,
            ConversationStatus expected,
            ConversationStatus target,
            Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        return save(new ConversationRecord(
                current.sessionId(),
                current.displayName(),
                current.createdAt(),
                at,
                current.revision() + 1,
                current.status()));
    }

    private ConversationRecord requireConversation(AgentSessionId id) {
        Indexed value = conversations.get(Objects.requireNonNull(id, "sessionId must not be null"));
        if (value == null) throw conflict("CONVERSATION_UNAVAILABLE");
        return value.record();
    }

    private ConversationRecord requireConversation(AgentSessionId id, long expectedRevision) {
        ConversationRecord value = requireConversation(id);
        if (value.revision() != expectedRevision) throw conflict("CONVERSATION_REVISION_STALE");
        return value;
    }

    private ConversationRecord save(ConversationRecord value) {
        Indexed existing = conversations.get(value.sessionId());
        conversations.put(value.sessionId(), new Indexed(existing.tenant(), existing.principal(), value));
        return value;
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }

    private record Indexed(TenantRef tenant, PrincipalRef principal, ConversationRecord record) {}
}
