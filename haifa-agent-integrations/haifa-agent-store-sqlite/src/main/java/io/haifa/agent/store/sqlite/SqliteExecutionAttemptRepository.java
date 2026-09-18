package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptPersistenceSnapshot;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.ExecutionAttemptRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.AgentErrorPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.PersistenceException;

/** SQLite/MyBatis implementation of execution-attempt persistence. */
public final class SqliteExecutionAttemptRepository implements ExecutionAttemptRepository {

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteExecutionAttemptRepository(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void insert(AgentRunExecutionAttempt attempt) {
        execute(() -> {
            try {
                unitOfWork.mapper(RuntimeStoreMapper.class).insertAttempt(toRow(attempt));
                return null;
            } catch (PersistenceException exception) {
                throw new IllegalStateException(
                        "attempt already exists or run has an active attempt: " + attempt.attemptId(), exception);
            }
        });
    }

    @Override
    public void save(AgentRunExecutionAttempt attempt, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        execute(() -> {
            int updated = unitOfWork.mapper(RuntimeStoreMapper.class).updateAttempt(toRow(attempt), expectedVersion);
            if (updated != 1) {
                throw new OptimisticLockException(
                        "attempt version conflict for " + attempt.attemptId().value() + " at " + expectedVersion);
            }
            return null;
        });
    }

    @Override
    public Optional<AgentRunExecutionAttempt> find(ExecutionAttemptId id) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findAttempt(id.value()))
                .map(this::fromRow));
    }

    @Override
    public Optional<AgentRunExecutionAttempt> activeFor(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).activeAttempt(runId.value()))
                .map(this::fromRow));
    }

    @Override
    public List<AgentRunExecutionAttempt> attemptsFor(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).attemptsForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    private ExecutionAttemptRow toRow(AgentRunExecutionAttempt attempt) {
        ExecutionAttemptPersistenceSnapshot value = attempt.persistenceSnapshot();
        EncodedPayload error = value.error() == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.AGENT_ERROR, AgentErrorPayload.from(value.error()));
        return new ExecutionAttemptRow(
                value.attemptId().value(),
                value.schemaVersion(),
                value.runId().value(),
                value.attemptNumber(),
                value.status(),
                value.createdAt(),
                value.startedAt(),
                value.heartbeatAt(),
                value.completedAt(),
                value.workerId(),
                value.resumedFromCheckpointId() == null
                        ? null
                        : value.resumedFromCheckpointId().value(),
                error == null ? null : error.schemaVersion(),
                error == null ? null : error.bytes(),
                error == null ? null : error.hash(),
                value.version());
    }

    private AgentRunExecutionAttempt fromRow(ExecutionAttemptRow row) {
        AgentError error = row.errorPayload() == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.AGENT_ERROR,
                                new EncodedPayload(
                                        SqliteRuntimePayloadTypes.AGENT_ERROR.name(),
                                        row.errorSchemaVersion(),
                                        row.errorPayload(),
                                        row.errorHash()))
                        .toDomain();
        return AgentRunExecutionAttempt.reconstitute(new ExecutionAttemptPersistenceSnapshot(
                row.schemaVersion(),
                new ExecutionAttemptId(row.attemptId()),
                new AgentRunId(row.runId()),
                row.attemptNumber(),
                row.createdAt(),
                row.resumedFromCheckpointId() == null ? null : new CheckpointId(row.resumedFromCheckpointId()),
                row.status(),
                row.startedAt(),
                row.heartbeatAt(),
                row.completedAt(),
                row.workerId(),
                error,
                row.version()));
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
