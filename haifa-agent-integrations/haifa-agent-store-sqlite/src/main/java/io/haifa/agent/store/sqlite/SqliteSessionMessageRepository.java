package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.storage.MessageRedactionListener;
import io.haifa.agent.runtime.core.storage.MessageRedactionListenerRegistry;
import io.haifa.agent.runtime.core.storage.RecentMessageWindow;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.storage.SessionMessageRepository;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.SessionMessageRow;
import io.haifa.agent.store.sqlite.payload.ContentPartsPayload;
import io.haifa.agent.store.sqlite.payload.MetadataPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.PersistenceException;

/** Ordered SQLite session-message component with after-commit redaction notifications. */
public final class SqliteSessionMessageRepository
        implements SessionMessageRepository, MessageRedactionListenerRegistry {

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final List<MessageRedactionListener> listeners = new CopyOnWriteArrayList<>();

    public SqliteSessionMessageRepository(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public AgentMessage appendSessionMessage(SessionMessageDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            long sequence = mapper.nextMessageSequence(draft.sessionId().value());
            SessionMessageRow row = toRow(draft, sequence);
            try {
                mapper.insertMessage(row);
            } catch (PersistenceException exception) {
                throw new IllegalStateException("message already exists or has invalid references", exception);
            }
            return fromRow(row);
        });
    }

    @Override
    public List<AgentMessage> messagesAfter(AgentSessionId sessionId, MessageCursor cursor, int limit) {
        requireLimit(limit);
        return execute(() ->
                unitOfWork
                        .mapper(RuntimeStoreMapper.class)
                        .messagesAfter(sessionId.value(), cursor.value(), limit)
                        .stream()
                        .map(this::fromRow)
                        .toList());
    }

    @Override
    public RecentMessageWindow recentMessages(AgentSessionId sessionId, MessageCursor atOrBefore, int limit) {
        requireLimit(limit);
        List<AgentMessage> messages = execute(() ->
                unitOfWork
                        .mapper(RuntimeStoreMapper.class)
                        .recentMessages(sessionId.value(), atOrBefore.value(), limit)
                        .stream()
                        .map(this::fromRow)
                        .toList());
        return messages.isEmpty()
                ? new RecentMessageWindow(sessionId, MessageCursor.BEFORE_FIRST, MessageCursor.BEFORE_FIRST, List.of())
                : new RecentMessageWindow(
                        sessionId,
                        messages.getFirst().cursor(),
                        messages.getLast().cursor(),
                        messages);
    }

    @Override
    public Optional<MessageCursor> latestMessageCursor(AgentSessionId sessionId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).latestMessageSequence(sessionId.value()))
                .map(MessageCursor::new));
    }

    @Override
    public Optional<AgentMessage> message(AgentMessageId id) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findMessage(id.value()))
                .map(this::fromRow));
    }

    public List<AgentMessage> messagesForRun(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).messagesForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    @Override
    public AgentMessage redactMessage(AgentMessageId id) {
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            SessionMessageRow currentRow = mapper.findMessage(id.value());
            if (currentRow == null) throw new IllegalArgumentException("unknown message: " + id.value());
            AgentMessage current = fromRow(currentRow);
            AgentMessage redacted = new AgentMessage(
                    current.id(),
                    current.sessionId(),
                    current.runId(),
                    current.parentMessageId(),
                    current.role(),
                    MessageStatus.REDACTED,
                    MessageVisibility.REDACTED,
                    current.sequence(),
                    List.of(new TextPart("[REDACTED]", "plain")),
                    Map.of("redacted", true),
                    current.createdAt());
            if (mapper.redactMessage(toRow(redacted)) != 1) {
                throw new IllegalStateException("message redaction was not applied");
            }
            mapper.invalidateSummariesForSession(current.sessionId().value());
            unitOfWork.afterCommit(() -> listeners.forEach(listener -> listener.onRedacted(current)));
            return redacted;
        });
    }

    @Override
    public void register(MessageRedactionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    private SessionMessageRow toRow(SessionMessageDraft draft, long sequence) {
        return row(
                draft.id(),
                draft.sessionId(),
                draft.runId(),
                draft.parentMessageId(),
                sequence,
                draft.role(),
                draft.status(),
                draft.visibility(),
                draft.contents(),
                draft.metadata(),
                draft.createdAt());
    }

    private SessionMessageRow toRow(AgentMessage message) {
        return row(
                message.id(),
                message.sessionId(),
                message.runId(),
                message.parentMessageId(),
                message.sequence(),
                message.role(),
                message.status(),
                message.visibility(),
                message.contents(),
                message.metadata(),
                message.createdAt());
    }

    private SessionMessageRow row(
            AgentMessageId id,
            AgentSessionId sessionId,
            Optional<AgentRunId> runId,
            Optional<AgentMessageId> parentMessageId,
            long sequence,
            MessageRole role,
            MessageStatus status,
            MessageVisibility visibility,
            List<io.haifa.agent.core.content.ContentPart> contents,
            Map<String, Object> metadata,
            java.time.Instant createdAt) {
        EncodedPayload content =
                codecs.encode(SqliteRuntimePayloadTypes.CONTENT_PARTS, ContentPartsPayload.from(contents));
        EncodedPayload metadataPayload =
                codecs.encode(SqliteRuntimePayloadTypes.METADATA, new MetadataPayload(metadata));
        return new SessionMessageRow(
                id.value(),
                sessionId.value(),
                runId.map(AgentRunId::value).orElse(null),
                parentMessageId.map(AgentMessageId::value).orElse(null),
                sequence,
                role.name(),
                status.name(),
                visibility.name(),
                content.schemaVersion(),
                content.bytes(),
                content.hash(),
                metadataPayload.schemaVersion(),
                metadataPayload.bytes(),
                metadataPayload.hash(),
                createdAt);
    }

    private AgentMessage fromRow(SessionMessageRow row) {
        ContentPartsPayload content = codecs.decode(
                SqliteRuntimePayloadTypes.CONTENT_PARTS,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.CONTENT_PARTS.name(),
                        row.contentSchemaVersion(),
                        row.contentPayload(),
                        row.contentHash()));
        MetadataPayload metadata = codecs.decode(
                SqliteRuntimePayloadTypes.METADATA,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.METADATA.name(),
                        row.metadataSchemaVersion(),
                        row.metadataPayload(),
                        row.metadataHash()));
        return new AgentMessage(
                new AgentMessageId(row.messageId()),
                new AgentSessionId(row.sessionId()),
                Optional.ofNullable(row.runId()).map(AgentRunId::new),
                Optional.ofNullable(row.parentMessageId()).map(AgentMessageId::new),
                MessageRole.valueOf(row.role()),
                MessageStatus.valueOf(row.status()),
                MessageVisibility.valueOf(row.visibility()),
                row.sequence(),
                content.toDomain(),
                metadata.values(),
                row.createdAt());
    }

    private static void requireLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}
