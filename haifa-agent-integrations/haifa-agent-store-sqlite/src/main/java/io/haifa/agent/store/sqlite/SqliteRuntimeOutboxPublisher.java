package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.OutboxRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeEventRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.OutboxPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class SqliteRuntimeOutboxPublisher implements RuntimeOutboxPublisher {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteRuntimeOutboxPublisher(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void append(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (!OutboxMessage.CURRENT_SCHEMA_VERSION.equals(message.schemaVersion())) {
            throw new IllegalArgumentException("unsupported outbox schema version");
        }
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            OutboxRow existing = mapper.findOutbox(message.id());
            if (existing != null) {
                if (!fromRow(existing).equals(message)) {
                    throw new IllegalStateException("outbox event id is already bound to different content");
                }
                return null;
            }
            RuntimeEventRow event = mapper.findEventBySequence(message.runId().value(), message.sequence());
            if (event == null || !event.type().equals(message.type())) {
                throw new IllegalStateException("outbox message has no matching runtime event");
            }
            String provisional = SqliteRuntimeEventAppender.provisionalId(message.runId(), message.sequence());
            if (!event.eventId().equals(message.id())) {
                if (!event.eventId().equals(provisional)
                        || mapper.replaceEventId(message.runId().value(), message.sequence(), provisional, message.id())
                                != 1) {
                    throw new IllegalStateException("runtime event id cannot be paired with outbox message");
                }
            }
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.OUTBOX, new OutboxPayload(message.payload()));
            mapper.insertOutbox(new OutboxRow(
                    message.id(),
                    message.runId().value(),
                    message.sequence(),
                    message.type(),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    message.createdAt(),
                    null));
            return null;
        });
    }

    @Override
    public List<OutboxMessage> pending() {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).pendingOutbox().stream()
                .map(this::fromRow)
                .toList());
    }

    @Override
    public void markPublished(String eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            if (mapper.findOutbox(eventId) == null) {
                throw new IllegalStateException("outbox message does not exist");
            }
            mapper.markOutboxPublished(eventId, clock.instant());
            return null;
        });
    }

    @Override
    public boolean markConsumed(String consumerId, String eventId) {
        Objects.requireNonNull(consumerId, "consumerId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        return execute(() ->
                unitOfWork.mapper(RuntimeStoreMapper.class).insertOutboxConsumer(consumerId, eventId, clock.instant())
                        == 1);
    }

    private OutboxMessage fromRow(OutboxRow row) {
        OutboxPayload payload = codecs.decode(
                SqliteRuntimePayloadTypes.OUTBOX,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.OUTBOX.name(),
                        row.payloadSchemaVersion(),
                        row.payload(),
                        row.payloadHash()));
        return new OutboxMessage(
                row.eventId(),
                new AgentRunId(row.runId()),
                row.sequence(),
                row.type(),
                row.payloadSchemaVersion(),
                payload.values(),
                row.createdAt());
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
