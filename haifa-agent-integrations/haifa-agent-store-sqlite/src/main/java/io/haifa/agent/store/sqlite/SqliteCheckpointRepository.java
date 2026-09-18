package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.checkpoint.CheckpointStatus;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointStateHasher;
import io.haifa.agent.runtime.core.storage.CheckpointRepository;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.CheckpointPayloadRow;
import io.haifa.agent.store.sqlite.mybatis.CheckpointRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Atomic metadata/payload SQLite checkpoint repository. */
public final class SqliteCheckpointRepository implements CheckpointRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteCheckpointRepository.class);

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteCheckpointRepository(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void append(Checkpoint checkpoint, RuntimeCheckpointState state) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (!checkpoint.runId().equals(state.runId())) {
            throw new IllegalArgumentException("checkpoint and state must belong to the same run");
        }
        long started = System.nanoTime();
        long phaseStarted = started;
        String stateHash = RuntimeCheckpointStateHasher.digest(state);
        long stateHashMillis = elapsedMillis(phaseStarted);
        if (!checkpoint.stateHash().equals(stateHash)) {
            throw new IllegalArgumentException("checkpoint state hash does not match runtime state");
        }
        PersistTiming timing = execute(() -> {
            long unitOfWorkId = unitOfWork.currentId();
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            long latestStarted = System.nanoTime();
            CheckpointRow latest = mapper.latestCheckpoint(checkpoint.runId().value());
            long latestMillis = elapsedMillis(latestStarted);
            long expected = latest == null ? 1 : Math.addExact(latest.sequence(), 1);
            if (checkpoint.sequence() != expected) {
                throw new IllegalArgumentException("checkpoint sequence must be monotonic");
            }
            long encodeStarted = System.nanoTime();
            EncodedPayload payload = codecs.encode(SqliteRuntimePayloadTypes.CHECKPOINT_STATE, state);
            long encodeMillis = elapsedMillis(encodeStarted);
            try {
                long metadataStarted = System.nanoTime();
                mapper.insertCheckpoint(toRow(checkpoint));
                long metadataInsertMillis = elapsedMillis(metadataStarted);
                long payloadStarted = System.nanoTime();
                mapper.insertCheckpointPayload(new CheckpointPayloadRow(
                        checkpoint.id().value(),
                        payload.schemaVersion(),
                        payload.bytes(),
                        stateHash,
                        payload.hash(),
                        checkpoint.createdAt()));
                return new PersistTiming(
                        unitOfWorkId,
                        latestMillis,
                        encodeMillis,
                        metadataInsertMillis,
                        elapsedMillis(payloadStarted),
                        payload.bytes().length);
            } catch (PersistenceException exception) {
                throw new IllegalStateException("checkpoint already exists or has invalid references", exception);
            }
        });
        LOGGER.info(
                "event=checkpoint.sqlite.persist runId={} checkpointId={} sequence={} uowId={} stateHashMs={} latestMs={} encodeMs={} metadataInsertMs={} payloadInsertMs={} payloadBytes={} totalMs={}",
                checkpoint.runId().value(),
                checkpoint.id().value(),
                checkpoint.sequence(),
                timing.unitOfWorkId(),
                stateHashMillis,
                timing.latestMillis(),
                timing.encodeMillis(),
                timing.metadataInsertMillis(),
                timing.payloadInsertMillis(),
                timing.payloadBytes(),
                elapsedMillis(started));
    }

    @Override
    public Optional<Checkpoint> latest(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).latestCheckpoint(runId.value()))
                .map(this::fromRow));
    }

    @Override
    public Optional<RuntimeCheckpointState> state(String checkpointId) {
        Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            CheckpointRow metadata = mapper.findCheckpoint(checkpointId);
            if (metadata == null) return Optional.empty();
            CheckpointPayloadRow payload = mapper.findCheckpointPayload(checkpointId);
            if (payload == null) throw corruption("checkpoint payload is missing");
            if (!metadata.stateHash().equals(payload.stateHash())) {
                throw corruption("checkpoint metadata and payload hashes differ");
            }
            RuntimeCheckpointState restored = codecs.decode(
                    SqliteRuntimePayloadTypes.CHECKPOINT_STATE,
                    new EncodedPayload(
                            SqliteRuntimePayloadTypes.CHECKPOINT_STATE.name(),
                            payload.stateSchemaVersion(),
                            payload.statePayload(),
                            payload.payloadHash()));
            if (!payload.stateHash().equals(RuntimeCheckpointStateHasher.digest(restored))) {
                throw corruption("checkpoint state integrity validation failed");
            }
            return Optional.of(restored);
        });
    }

    private static SqliteStoreException corruption(String message) {
        return new SqliteStoreException(SqliteStoreFailure.CHECKPOINT_CORRUPTION, message);
    }

    @Override
    public List<Checkpoint> checkpointsFor(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).checkpointsForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    private static CheckpointRow toRow(Checkpoint value) {
        return new CheckpointRow(
                value.id().value(),
                value.runId().value(),
                value.stepId().map(AgentStepId::value).orElse(null),
                value.type().name(),
                value.status().name(),
                value.sequence(),
                value.payload().storeType(),
                value.payload().location(),
                value.payload().schemaId(),
                value.payload().schemaVersion(),
                value.stateHash(),
                value.createdAt());
    }

    private Checkpoint fromRow(CheckpointRow row) {
        return new Checkpoint(
                new CheckpointId(row.checkpointId()),
                new AgentRunId(row.runId()),
                Optional.ofNullable(row.stepId()).map(AgentStepId::new),
                CheckpointType.valueOf(row.type()),
                CheckpointStatus.valueOf(row.status()),
                row.sequence(),
                new CheckpointPayloadRef(
                        row.payloadStoreType(),
                        row.payloadLocation(),
                        row.payloadSchemaId(),
                        row.payloadSchemaVersion()),
                row.stateHash(),
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

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record PersistTiming(
            long unitOfWorkId,
            long latestMillis,
            long encodeMillis,
            long metadataInsertMillis,
            long payloadInsertMillis,
            int payloadBytes) {}
}
