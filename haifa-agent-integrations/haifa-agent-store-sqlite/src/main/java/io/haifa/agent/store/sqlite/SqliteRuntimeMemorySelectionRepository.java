package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelection;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelectionRepository;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.MemorySelectionRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.MemorySelectionPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteRuntimeMemorySelectionRepository implements RuntimeMemorySelectionRepository {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteRuntimeMemorySelectionRepository(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.codecs = Objects.requireNonNull(codecs);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void saveMemorySelection(AgentRunId runId, RuntimeMemorySelection selection) {
        execute(() -> {
            EncodedPayload payload = codecs.encode(
                    SqliteRuntimePayloadTypes.MEMORY_SELECTION, new MemorySelectionPayload(selection.memories()));
            unitOfWork
                    .mapper(RuntimeStoreMapper.class)
                    .upsertMemorySelection(new MemorySelectionRow(
                            runId.value(),
                            selection.retrievalPolicyVersion(),
                            selection.queryDigest(),
                            payload.schemaVersion(),
                            payload.bytes(),
                            payload.hash(),
                            clock.instant()));
            return null;
        });
    }

    @Override
    public Optional<RuntimeMemorySelection> memorySelection(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findMemorySelection(runId.value()))
                .map(this::fromRow));
    }

    private RuntimeMemorySelection fromRow(MemorySelectionRow row) {
        MemorySelectionPayload payload = codecs.decode(
                SqliteRuntimePayloadTypes.MEMORY_SELECTION,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.MEMORY_SELECTION.name(),
                        row.memoriesSchemaVersion(),
                        row.memoriesPayload(),
                        row.memoriesHash()));
        return new RuntimeMemorySelection(payload.memories(), row.retrievalPolicyVersion(), row.queryDigest());
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
