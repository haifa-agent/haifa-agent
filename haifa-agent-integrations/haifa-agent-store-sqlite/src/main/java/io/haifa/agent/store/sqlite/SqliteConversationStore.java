package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.store.sqlite.mybatis.SdkConversationMapper;
import io.haifa.agent.store.sqlite.mybatis.SdkConversationRow;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

public final class SqliteConversationStore implements ConversationStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteConversationStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public ConversationRecord create(ConversationRecord conversation, TenantRef tenant, PrincipalRef principal) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            SdkConversationRow existing =
                    mapper.findConversation(conversation.sessionId().value());
            if (existing != null) {
                if (!existing.tenantId().equals(tenant.tenantId())
                        || !existing.principalId().equals(principal.principalId())
                        || !existing.principalType().equals(principal.principalType())) {
                    throw conflict("CONVERSATION_SCOPE_CONFLICT");
                }
                return fromRow(existing);
            }
            if (mapper.insertConversation(toRow(conversation, tenant, principal)) != 1) {
                throw conflict("CONVERSATION_WRITE_FAILED");
            }
            return conversation;
        });
    }

    @Override
    public Optional<ConversationRecord> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(SdkConversationMapper.class).findConversation(sessionId.value()))
                .map(SqliteConversationStore::fromRow));
    }

    @Override
    public List<ConversationRecord> list(TenantRef tenant, PrincipalRef principal, ConversationQuery query) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(query, "query must not be null");
        return execute(() -> unitOfWork
                .mapper(SdkConversationMapper.class)
                .listConversations(
                        tenant.tenantId(),
                        principal.principalId(),
                        principal.principalType(),
                        query.text().map(SqliteConversationStore::escapeLike).orElse(null),
                        query.after().map(value -> value.lastActivityAt()).orElse(null),
                        query.after().map(value -> value.sessionId().value()).orElse(null),
                        query.limit() + 1)
                .stream()
                .map(SqliteConversationStore::fromRow)
                .toList());
    }

    @Override
    public ConversationRecord touchLastActivity(AgentSessionId sessionId, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        return update(sessionId, mapper -> mapper.touchLastActivity(sessionId.value(), at), "CONVERSATION_UNAVAILABLE");
    }

    @Override
    public ConversationRecord rename(AgentSessionId sessionId, long expectedRevision, String displayName, Instant at) {
        return update(
                sessionId,
                mapper -> mapper.rename(sessionId.value(), expectedRevision, displayName, at),
                "CONVERSATION_REVISION_STALE");
    }

    @Override
    public ConversationRecord changeStatus(AgentSessionId sessionId, long expectedRevision, Instant at) {
        return update(
                sessionId,
                mapper -> mapper.changeStatus(sessionId.value(), expectedRevision, at),
                "CONVERSATION_REVISION_STALE");
    }

    private ConversationRecord update(
            AgentSessionId sessionId, ToIntFunction<SdkConversationMapper> work, String code) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            if (work.applyAsInt(mapper) != 1) throw conflict(code);
            return fromRow(requireConversation(mapper, sessionId));
        });
    }

    private static SdkConversationRow requireConversation(SdkConversationMapper mapper, AgentSessionId sessionId) {
        SdkConversationRow row = mapper.findConversation(sessionId.value());
        if (row == null) throw conflict("CONVERSATION_UNAVAILABLE");
        return row;
    }

    private static SdkConversationRow toRow(ConversationRecord value, TenantRef tenant, PrincipalRef principal) {
        return new SdkConversationRow(
                value.sessionId().value(),
                tenant.tenantId(),
                principal.principalId(),
                principal.principalType(),
                value.displayName(),
                value.createdAt(),
                value.lastActivityAt(),
                value.revision());
    }

    private static ConversationRecord fromRow(SdkConversationRow row) {
        return new ConversationRecord(
                new AgentSessionId(row.sessionId()),
                row.displayName(),
                row.createdAt(),
                row.lastActivityAt(),
                row.revision(),
                ConversationStatus.ACTIVE);
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private <T> T execute(java.util.function.Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof IllegalStateException conflict) {
                throw conflict;
            }
            throw exception;
        }
    }
}
