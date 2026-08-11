package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.core.storage.IdempotencyRepository;
import io.haifa.agent.runtime.core.storage.RunStartIdempotencyBinding;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.IdempotencyRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.CommandResultPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteIdempotencyRepository implements IdempotencyRepository {
    private static final String COMMAND = "command";
    private static final String COMMAND_RESULT = "command-result";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteIdempotencyRepository(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<RunStartIdempotencyBinding> findRunBinding(String callerScope, String operation, String key) {
        return execute(() -> Optional.ofNullable(find(callerScope, operation, key))
                .map(row -> new RunStartIdempotencyBinding(
                        row.callerScope(),
                        row.operation(),
                        row.idempotencyKey(),
                        Optional.ofNullable(row.requestDigest()),
                        new AgentRunId(row.runId()))));
    }

    @Override
    public RunStartIdempotencyBinding recordRunBinding(RunStartIdempotencyBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            Instant now = Instant.ofEpochMilli(clock.millis());
            mapper.insertIdempotency(new IdempotencyRow(
                    binding.callerScope(),
                    binding.operation(),
                    binding.idempotencyKey(),
                    binding.runId().value(),
                    binding.requestDigest().orElse(null),
                    false,
                    null,
                    null,
                    null,
                    now,
                    now));
            IdempotencyRow stored =
                    mapper.findIdempotency(binding.callerScope(), binding.operation(), binding.idempotencyKey());
            if (stored == null) throw new IllegalStateException("idempotency binding was not recorded");
            return new RunStartIdempotencyBinding(
                    stored.callerScope(),
                    stored.operation(),
                    stored.idempotencyKey(),
                    Optional.ofNullable(stored.requestDigest()),
                    new AgentRunId(stored.runId()));
        });
    }

    @Override
    public AgentRunId recordRun(String callerScope, String operation, String key, AgentRunId runId) {
        RunStartIdempotencyBinding stored =
                recordRunBinding(new RunStartIdempotencyBinding(callerScope, operation, key, Optional.empty(), runId));
        if (!stored.runId().equals(runId)) {
            throw new IllegalStateException("idempotency key is already bound to a different run");
        }
        return stored.runId();
    }

    @Override
    public boolean markCommandApplied(String callerScope, String key) {
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            Instant now = Instant.ofEpochMilli(clock.millis());
            mapper.insertIdempotency(
                    new IdempotencyRow(callerScope, COMMAND, key, null, null, false, null, null, null, now, now));
            return mapper.markCommandApplied(callerScope, key, now) == 1;
        });
    }

    @Override
    public Optional<RuntimeCommandResult> findCommandResult(String callerScope, String idempotencyKey) {
        return execute(() -> {
            IdempotencyRow row = find(callerScope, COMMAND_RESULT, idempotencyKey);
            if (row == null || row.resultPayload() == null) return Optional.empty();
            CommandResultPayload payload = codecs.decode(
                    SqliteRuntimePayloadTypes.COMMAND_RESULT,
                    new EncodedPayload(
                            SqliteRuntimePayloadTypes.COMMAND_RESULT.name(),
                            row.resultSchemaVersion(),
                            row.resultPayload(),
                            row.resultHash()));
            return Optional.of(payload.toDomain());
        });
    }

    @Override
    public void recordCommandResult(String callerScope, String idempotencyKey, RuntimeCommandResult result) {
        Objects.requireNonNull(result, "result must not be null");
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.COMMAND_RESULT, CommandResultPayload.from(result));
            Instant now = Instant.ofEpochMilli(clock.millis());
            IdempotencyRow row = new IdempotencyRow(
                    callerScope,
                    COMMAND_RESULT,
                    idempotencyKey,
                    result.command().runId().value(),
                    null,
                    false,
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    now,
                    now);
            mapper.insertIdempotency(row);
            IdempotencyRow stored = mapper.findIdempotency(callerScope, COMMAND_RESULT, idempotencyKey);
            if (stored == null
                    || !result.command().runId().value().equals(stored.runId())
                    || stored.resultPayload() == null) {
                throw new IllegalStateException("command result idempotency key has conflicting content");
            }
            CommandResultPayload decoded = codecs.decode(
                    SqliteRuntimePayloadTypes.COMMAND_RESULT,
                    new EncodedPayload(
                            SqliteRuntimePayloadTypes.COMMAND_RESULT.name(),
                            stored.resultSchemaVersion(),
                            stored.resultPayload(),
                            stored.resultHash()));
            if (!decoded.toDomain().equals(result)) {
                throw new IllegalStateException("command result idempotency key has conflicting content");
            }
            return null;
        });
    }

    private IdempotencyRow find(String callerScope, String operation, String key) {
        requireText(callerScope, "callerScope");
        requireText(operation, "operation");
        requireText(key, "key");
        return unitOfWork.mapper(RuntimeStoreMapper.class).findIdempotency(callerScope, operation, key);
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field + " must not be null").trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
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
