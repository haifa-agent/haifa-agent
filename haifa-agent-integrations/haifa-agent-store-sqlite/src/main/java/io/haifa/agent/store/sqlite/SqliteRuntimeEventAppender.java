package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
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
import java.util.function.Supplier;

public final class SqliteRuntimeEventAppender implements RuntimeEventAppender {
    static final String PROVISIONAL_ID_PREFIX = "runtime-event:";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteRuntimeEventAppender(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            long sequence = mapper.nextEventSequence(runId.value());
            RuntimeEvent event = new RuntimeEvent(runId, sequence, type, data, occurredAt);
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.EVENT_DATA, new EventDataPayload(event.data()));
            mapper.insertEvent(new RuntimeEventRow(
                    provisionalId(runId, sequence),
                    runId.value(),
                    sequence,
                    event.type(),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    event.occurredAt()));
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

    static String provisionalId(AgentRunId runId, long sequence) {
        return PROVISIONAL_ID_PREFIX + runId.value() + ":" + sequence;
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
                new AgentRunId(row.runId()), row.sequence(), row.type(), payload.values(), row.occurredAt());
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
