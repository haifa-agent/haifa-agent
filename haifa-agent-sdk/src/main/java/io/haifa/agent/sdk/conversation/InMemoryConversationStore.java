package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class InMemoryConversationStore implements ConversationStore {
    private final Map<String, ConversationCommandBinding> commands = new LinkedHashMap<>();
    private final Map<AgentSessionId, ConversationRecord> conversations = new LinkedHashMap<>();

    @Override
    public synchronized ConversationCommandBinding reserveCommand(ConversationCommandBinding command) {
        ConversationCommandBinding existing = commands.values().stream()
                .filter(value -> value.callerScopeDigest().equals(command.callerScopeDigest())
                        && value.operation().equals(command.operation())
                        && value.idempotencyKeyDigest().equals(command.idempotencyKeyDigest()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(command.requestDigest())) {
                throw conflict("CONVERSATION_IDEMPOTENCY_CONFLICT");
            }
            return existing;
        }
        if (commands.putIfAbsent(command.dispatchKey(), command) != null) {
            throw conflict("CONVERSATION_DISPATCH_CONFLICT");
        }
        return command;
    }

    @Override
    public synchronized Optional<ConversationCommandBinding> findCommand(String dispatchKey) {
        return Optional.ofNullable(commands.get(dispatchKey));
    }

    @Override
    public synchronized ConversationCommandBinding completeCommand(
            String dispatchKey, Optional<AgentRunId> runId, long resultRevision) {
        ConversationCommandBinding existing = requireCommand(dispatchKey);
        if (existing.completed()) {
            if (!existing.runId().equals(runId) || existing.resultRevision().orElseThrow() != resultRevision) {
                throw conflict("CONVERSATION_COMMAND_RUN_CONFLICT");
            }
            return existing;
        }
        ConversationCommandBinding completed = new ConversationCommandBinding(
                existing.callerScopeDigest(),
                existing.operation(),
                existing.idempotencyKeyDigest(),
                existing.requestDigest(),
                existing.dispatchKey(),
                existing.sessionId(),
                runId,
                true,
                OptionalLong.of(resultRevision),
                existing.createdAt());
        commands.put(dispatchKey, completed);
        return completed;
    }

    @Override
    public synchronized ConversationRecord create(ConversationRecord conversation) {
        ConversationRecord existing = conversations.putIfAbsent(conversation.sessionId(), conversation);
        if (existing != null) {
            if (!existing.tenant().equals(conversation.tenant())
                    || !existing.principal().equals(conversation.principal())) {
                throw conflict("CONVERSATION_SCOPE_CONFLICT");
            }
            return existing;
        }
        return conversation;
    }

    @Override
    public synchronized Optional<ConversationRecord> find(AgentSessionId sessionId) {
        return Optional.ofNullable(conversations.get(sessionId));
    }

    @Override
    public synchronized List<ConversationRecord> list(
            TenantRef tenant, PrincipalRef principal, ConversationQuery query) {
        Comparator<ConversationRecord> order = Comparator.comparing(ConversationRecord::lastActivityAt)
                .reversed()
                .thenComparing(value -> value.sessionId().value(), Comparator.reverseOrder());
        return conversations.values().stream()
                .filter(value ->
                        value.tenant().equals(tenant) && value.principal().equals(principal))
                .filter(value -> query.statuses().contains(value.status()))
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
    public synchronized ConversationRecord reserveActive(
            AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant at) {
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        if (current.activeRunId().isPresent() || current.activeDispatchKey().isPresent()) {
            throw conflict("CONVERSATION_ACTIVE");
        }
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                current.status(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.of(dispatchKey),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    @Override
    public synchronized ConversationRecord activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant at) {
        ConversationRecord current = requireConversation(sessionId);
        if (current.activeRunId().filter(runId::equals).isPresent()) return current;
        if (current.activeDispatchKey().filter(dispatchKey::equals).isEmpty()) {
            throw conflict("CONVERSATION_DISPATCH_STALE");
        }
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                current.status(),
                Optional.of(runId),
                OptionalLong.of(runVersion),
                Optional.empty(),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    @Override
    public synchronized ConversationRecord releasePendingDispatch(
            AgentSessionId sessionId, String dispatchKey, long expectedRevision, Instant at) {
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        if (current.activeRunId().isPresent()
                || current.activeDispatchKey().filter(dispatchKey::equals).isEmpty()) {
            throw conflict("CONVERSATION_DISPATCH_STALE");
        }
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                current.status(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    @Override
    public synchronized ConversationRecord clearActive(
            AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant at) {
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        if (current.activeRunId().filter(runId::equals).isEmpty()) {
            throw conflict("CONVERSATION_ACTIVE_RUN_MISMATCH");
        }
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                current.status(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    @Override
    public synchronized ConversationRecord rename(
            AgentSessionId sessionId, long expectedRevision, String displayName, Instant at) {
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                displayName,
                current.status(),
                current.activeRunId(),
                current.activeRunVersion(),
                current.activeDispatchKey(),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    @Override
    public synchronized ConversationRecord changeStatus(
            AgentSessionId sessionId,
            long expectedRevision,
            ConversationStatus expected,
            ConversationStatus target,
            Instant at) {
        ConversationRecord current = requireConversation(sessionId, expectedRevision);
        if (current.status() != expected) throw conflict("CONVERSATION_STATUS_STALE");
        if (current.activeRunId().isPresent() || current.activeDispatchKey().isPresent()) {
            throw conflict("CONVERSATION_ACTIVE");
        }
        return save(new ConversationRecord(
                current.sessionId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                target,
                current.activeRunId(),
                current.activeRunVersion(),
                current.activeDispatchKey(),
                current.createdAt(),
                at,
                current.revision() + 1));
    }

    private ConversationCommandBinding requireCommand(String dispatchKey) {
        ConversationCommandBinding value = commands.get(dispatchKey);
        if (value == null) throw conflict("CONVERSATION_COMMAND_UNAVAILABLE");
        return value;
    }

    private ConversationRecord requireConversation(AgentSessionId id) {
        ConversationRecord value = conversations.get(id);
        if (value == null) throw conflict("CONVERSATION_UNAVAILABLE");
        return value;
    }

    private ConversationRecord requireConversation(AgentSessionId id, long expectedRevision) {
        ConversationRecord value = requireConversation(id);
        if (value.revision() != expectedRevision) throw conflict("CONVERSATION_REVISION_STALE");
        return value;
    }

    private ConversationRecord save(ConversationRecord value) {
        conversations.put(value.sessionId(), value);
        return value;
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }
}
