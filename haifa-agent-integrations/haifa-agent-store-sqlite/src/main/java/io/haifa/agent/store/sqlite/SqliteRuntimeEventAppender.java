package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeEventIdFactory;
import io.haifa.agent.runtime.core.storage.RuntimeEventSlice;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeEventRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.EventDataPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

public final class SqliteRuntimeEventAppender implements RuntimeEventAppender {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final RuntimeEventIdFactory eventIds;

    public SqliteRuntimeEventAppender(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this(unitOfWork, codecs, RuntimeEventIdFactory.deterministic());
    }

    public SqliteRuntimeEventAppender(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, RuntimeEventIdFactory eventIds) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds must not be null");
    }

    @Override
    public RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            mapper.ensureEventStream(runId.value(), 0L, 1L, occurredAt);
            Long currentHead = mapper.eventHead(runId.value());
            if (currentHead == null) throw new IllegalStateException("runtime event stream is missing");
            long sequence = Math.addExact(currentHead, 1L);
            RuntimeEvent event =
                    new RuntimeEvent(eventIds.create(runId, sequence), runId, sequence, type, data, occurredAt);
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.EVENT_DATA, new EventDataPayload(event.data()));
            if (mapper.advanceEventHead(runId.value(), currentHead, sequence, occurredAt) != 1) {
                throw new IllegalStateException("runtime event stream head changed concurrently");
            }
            mapper.insertEvent(new RuntimeEventRow(
                    event.eventId(),
                    runId.value(),
                    sequence,
                    event.type(),
                    event.eventSchemaVersion(),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    event.occurredAt(),
                    event.correlationId().orElse(null),
                    event.causationId().orElse(null)));
            return event;
        });
    }

    @Override
    public List<RuntimeEvent> eventsFor(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).eventsForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    @Override
    public RuntimeEventSlice eventsAfter(
            AgentRunId runId, long exclusiveSequence, OptionalLong observedHead, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        validateRange(exclusiveSequence, observedHead, limit);
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            Long storedHead = mapper.eventHead(runId.value());
            if (storedHead == null) {
                return new RuntimeEventSlice(
                        runId,
                        exclusiveSequence,
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        exclusiveSequence,
                        List.of());
            }
            long boundedHead = observedHead.isPresent() ? Math.min(observedHead.getAsLong(), storedHead) : storedHead;
            List<RuntimeEvent> selected =
                    mapper.eventsAfter(runId.value(), exclusiveSequence, boundedHead, limit).stream()
                            .map(this::fromRow)
                            .toList();
            long scannedThrough =
                    selected.isEmpty() ? exclusiveSequence : selected.getLast().sequence();
            Long earliest = mapper.eventEarliest(runId.value());
            return new RuntimeEventSlice(
                    runId,
                    exclusiveSequence,
                    earliest == null ? OptionalLong.empty() : OptionalLong.of(earliest),
                    OptionalLong.of(boundedHead),
                    scannedThrough,
                    selected);
        });
    }

    @Override
    public OptionalLong earliestSequence(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        Long value = execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).eventEarliest(runId.value()));
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public OptionalLong headSequence(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        Long value = execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).eventHead(runId.value()));
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public long deleteBefore(AgentRunId runId, long retainFromSequence, Instant deletedAt) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(deletedAt, "deletedAt must not be null");
        if (retainFromSequence < 1) throw new IllegalArgumentException("retainFromSequence must be positive");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            Long head = mapper.eventHead(runId.value());
            if (head == null) return 0L;
            mapper.deletePublishedOutboxBefore(runId.value(), retainFromSequence);
            long deleted = mapper.deleteEventsBefore(runId.value(), retainFromSequence);
            Long minimum = mapper.minimumStoredEventSequence(runId.value());
            long earliest = minimum == null ? Math.addExact(head, 1L) : minimum;
            if (mapper.updateEventEarliest(runId.value(), earliest, deletedAt) != 1) {
                throw new IllegalStateException("runtime event stream is missing");
            }
            return deleted;
        });
    }

    private RuntimeEvent fromRow(RuntimeEventRow row) {
        EventDataPayload payload = codecs.decode(
                SqliteRuntimePayloadTypes.EVENT_DATA,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.EVENT_DATA.name(),
                        row.dataSchemaVersion(),
                        row.dataPayload(),
                        row.dataHash()));
        return new RuntimeEvent(
                row.eventId(),
                new AgentRunId(row.runId()),
                row.sequence(),
                row.type(),
                row.eventSchemaVersion(),
                payload.values(),
                row.occurredAt(),
                Optional.ofNullable(row.correlationId()),
                Optional.ofNullable(row.causationId()));
    }

    private static void validateRange(long exclusiveSequence, OptionalLong observedHead, int limit) {
        if (exclusiveSequence < 0) throw new IllegalArgumentException("exclusiveSequence must not be negative");
        Objects.requireNonNull(observedHead, "observedHead must not be null");
        if (observedHead.isPresent() && observedHead.getAsLong() < 1) {
            throw new IllegalArgumentException("observedHead must be positive");
        }
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be in 1..10000");
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
