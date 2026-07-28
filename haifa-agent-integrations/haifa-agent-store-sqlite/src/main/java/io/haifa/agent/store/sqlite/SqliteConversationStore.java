package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.sdk.conversation.ConversationCommandBinding;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.store.sqlite.mybatis.SdkConversationCommandRow;
import io.haifa.agent.store.sqlite.mybatis.SdkConversationMapper;
import io.haifa.agent.store.sqlite.mybatis.SdkConversationRow;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class SqliteConversationStore implements ConversationStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteConversationStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public ConversationCommandBinding reserveCommand(ConversationCommandBinding command) {
        Objects.requireNonNull(command, "command must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            SdkConversationCommandRow existing = mapper.findCommandByIdempotency(
                    command.callerScopeDigest(), command.operation(), command.idempotencyKeyDigest());
            if (existing != null) {
                if (!existing.requestDigest().equals(command.requestDigest())) {
                    throw conflict("CONVERSATION_IDEMPOTENCY_CONFLICT");
                }
                return fromRow(existing);
            }
            if (mapper.findCommandByDispatchKey(command.dispatchKey()) != null) {
                throw conflict("CONVERSATION_DISPATCH_CONFLICT");
            }
            if (mapper.insertCommand(toRow(command)) != 1) {
                throw conflict("CONVERSATION_COMMAND_WRITE_FAILED");
            }
            return command;
        });
    }

    @Override
    public Optional<ConversationCommandBinding> findCommand(String dispatchKey) {
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(SdkConversationMapper.class).findCommandByDispatchKey(dispatchKey))
                .map(SqliteConversationStore::fromRow));
    }

    @Override
    public ConversationCommandBinding completeCommand(
            String dispatchKey, Optional<AgentRunId> runId, long resultRevision) {
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            if (mapper.completeCommand(dispatchKey, runId.map(AgentRunId::value).orElse(null), resultRevision) != 1) {
                throw conflict("CONVERSATION_COMMAND_RUN_CONFLICT");
            }
            return fromRow(requireCommand(mapper, dispatchKey));
        });
    }

    @Override
    public ConversationRecord create(ConversationRecord conversation) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            SdkConversationRow existing =
                    mapper.findConversation(conversation.sessionId().value());
            if (existing != null) {
                ConversationRecord current = fromRow(existing);
                if (!current.tenant().equals(conversation.tenant())
                        || !current.principal().equals(conversation.principal())) {
                    throw conflict("CONVERSATION_SCOPE_CONFLICT");
                }
                return current;
            }
            if (mapper.insertConversation(toRow(conversation)) != 1) {
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
                        query.statuses().stream().map(Enum::name).sorted().toList(),
                        query.text().map(SqliteConversationStore::escapeLike).orElse(null),
                        query.after().map(value -> value.lastActivityAt()).orElse(null),
                        query.after().map(value -> value.sessionId().value()).orElse(null),
                        query.limit() + 1)
                .stream()
                .map(SqliteConversationStore::fromRow)
                .toList());
    }

    @Override
    public ConversationRecord reserveActive(
            AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant at) {
        return update(
                sessionId,
                mapper -> mapper.reserveActive(sessionId.value(), expectedRevision, dispatchKey, at),
                "CONVERSATION_ACTIVE");
    }

    @Override
    public ConversationRecord activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant at) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            ConversationRecord current = fromRow(requireConversation(mapper, sessionId));
            if (current.activeRunId().filter(runId::equals).isPresent()) return current;
            if (mapper.activateRun(sessionId.value(), dispatchKey, runId.value(), runVersion, at) != 1) {
                throw conflict("CONVERSATION_DISPATCH_STALE");
            }
            return fromRow(requireConversation(mapper, sessionId));
        });
    }

    @Override
    public ConversationRecord clearActive(
            AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant at) {
        Objects.requireNonNull(runId, "runId must not be null");
        return update(
                sessionId,
                mapper -> mapper.clearActive(sessionId.value(), runId.value(), expectedRevision, at),
                "CONVERSATION_ACTIVE_RUN_MISMATCH");
    }

    @Override
    public ConversationRecord rename(AgentSessionId sessionId, long expectedRevision, String displayName, Instant at) {
        return update(
                sessionId,
                mapper -> mapper.rename(sessionId.value(), expectedRevision, displayName, at),
                "CONVERSATION_REVISION_STALE");
    }

    @Override
    public ConversationRecord changeStatus(
            AgentSessionId sessionId,
            long expectedRevision,
            ConversationStatus expected,
            ConversationStatus target,
            Instant at) {
        return update(
                sessionId,
                mapper -> mapper.changeStatus(sessionId.value(), expectedRevision, expected.name(), target.name(), at),
                "CONVERSATION_STATUS_STALE");
    }

    private ConversationRecord update(
            AgentSessionId sessionId, java.util.function.ToIntFunction<SdkConversationMapper> work, String code) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return execute(() -> {
            SdkConversationMapper mapper = unitOfWork.mapper(SdkConversationMapper.class);
            if (work.applyAsInt(mapper) != 1) throw conflict(code);
            return fromRow(requireConversation(mapper, sessionId));
        });
    }

    private static SdkConversationCommandRow requireCommand(SdkConversationMapper mapper, String dispatchKey) {
        SdkConversationCommandRow row = mapper.findCommandByDispatchKey(dispatchKey);
        if (row == null) throw conflict("CONVERSATION_COMMAND_UNAVAILABLE");
        return row;
    }

    private static SdkConversationRow requireConversation(SdkConversationMapper mapper, AgentSessionId sessionId) {
        SdkConversationRow row = mapper.findConversation(sessionId.value());
        if (row == null) throw conflict("CONVERSATION_UNAVAILABLE");
        return row;
    }

    private static SdkConversationCommandRow toRow(ConversationCommandBinding value) {
        return new SdkConversationCommandRow(
                value.dispatchKey(),
                value.callerScopeDigest(),
                value.operation(),
                value.idempotencyKeyDigest(),
                value.requestDigest(),
                value.sessionId().value(),
                value.runId().map(AgentRunId::value).orElse(null),
                value.completed(),
                value.resultRevision().isPresent() ? value.resultRevision().getAsLong() : null,
                value.createdAt());
    }

    private static ConversationCommandBinding fromRow(SdkConversationCommandRow row) {
        return new ConversationCommandBinding(
                row.callerScopeDigest(),
                row.operation(),
                row.idempotencyKeyDigest(),
                row.requestDigest(),
                row.dispatchKey(),
                new AgentSessionId(row.sessionId()),
                Optional.ofNullable(row.runId()).map(AgentRunId::new),
                row.completed(),
                row.resultRevision() == null ? OptionalLong.empty() : OptionalLong.of(row.resultRevision()),
                row.createdAt());
    }

    private static SdkConversationRow toRow(ConversationRecord value) {
        return new SdkConversationRow(
                value.sessionId().value(),
                value.tenant().tenantId(),
                value.principal().principalId(),
                value.principal().principalType(),
                value.displayName(),
                value.status().name(),
                value.activeRunId().map(AgentRunId::value).orElse(null),
                value.activeRunVersion().isPresent() ? value.activeRunVersion().getAsLong() : null,
                value.activeDispatchKey().orElse(null),
                value.createdAt(),
                value.lastActivityAt(),
                value.revision());
    }

    private static ConversationRecord fromRow(SdkConversationRow row) {
        return new ConversationRecord(
                new AgentSessionId(row.sessionId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                row.displayName(),
                ConversationStatus.valueOf(row.status()),
                Optional.ofNullable(row.activeRunId()).map(AgentRunId::new),
                row.activeRunVersion() == null ? OptionalLong.empty() : OptionalLong.of(row.activeRunVersion()),
                Optional.ofNullable(row.activeDispatchKey()),
                row.createdAt(),
                row.lastActivityAt(),
                row.revision());
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
