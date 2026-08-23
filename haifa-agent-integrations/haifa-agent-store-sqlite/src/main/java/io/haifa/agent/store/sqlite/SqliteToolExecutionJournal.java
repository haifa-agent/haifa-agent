package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.tool.ToolExecutionJournal;
import io.haifa.agent.runtime.core.tool.ToolJournalState;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.ToolJournalRow;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import io.haifa.agent.store.sqlite.payload.ToolResultPayload;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolReconciliationRecord;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteToolExecutionJournal implements ToolExecutionJournal {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteToolExecutionJournal(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<ToolResult> completed(AgentRunId runId, RuntimeIdempotencyKey key) {
        return result(runId, key, ToolJournalState.COMPLETED);
    }

    @Override
    public Optional<ToolResult> pendingResult(AgentRunId runId, RuntimeIdempotencyKey key) {
        return result(runId, key, ToolJournalState.PENDING_RESULT);
    }

    @Override
    public Optional<ToolResult> uncertainResult(AgentRunId runId, RuntimeIdempotencyKey key) {
        return result(runId, key, ToolJournalState.OUTCOME_UNKNOWN);
    }

    @Override
    public void recordIntent(AgentRunId runId, RuntimeIdempotencyKey key) {
        recordIntent(runId, key, ToolIdempotency.UNKNOWN);
    }

    @Override
    public void recordIntent(AgentRunId runId, RuntimeIdempotencyKey key, ToolIdempotency toolIdempotency) {
        Objects.requireNonNull(toolIdempotency, "toolIdempotency must not be null");
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            ToolJournalRow existing = mapper.findToolJournal(runId.value(), key.value());
            if (existing != null) {
                if (ToolJournalState.valueOf(existing.state()) == ToolJournalState.INTENT_RECORDED) return null;
                throw new IllegalStateException("tool journal intent already advanced");
            }
            Instant now = Instant.ofEpochMilli(clock.millis());
            mapper.insertToolJournal(new ToolJournalRow(
                    runId.value(),
                    key.value(),
                    ToolJournalState.INTENT_RECORDED.name(),
                    toolIdempotency.name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    now));
            return null;
        });
    }

    @Override
    public void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey key) {
        transition(
                runId,
                key,
                ToolJournalState.DISPATCHED,
                null,
                EnumSet.of(ToolJournalState.INTENT_RECORDED),
                EnumSet.of(ToolJournalState.ACKNOWLEDGED));
    }

    @Override
    public void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey key, ToolDispatchEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            ToolJournalRow current = mapper.findToolJournal(runId.value(), key.value());
            if (current == null) throw new IllegalStateException("tool journal intent does not exist");
            ToolJournalState currentState = ToolJournalState.valueOf(current.state());
            if (currentState != ToolJournalState.INTENT_RECORDED
                    && currentState != ToolJournalState.DISPATCHED
                    && currentState != ToolJournalState.ACKNOWLEDGED) {
                throw new IllegalStateException("tool is not at a dispatch boundary");
            }
            ToolDispatchEvidence existing = dispatchEvidence(current);
            if (existing != null && !existing.equals(evidence)) {
                throw new IllegalStateException("tool dispatch evidence changed for the same idempotency key");
            }
            if (existing != null && currentState != ToolJournalState.INTENT_RECORDED) return null;
            String nextState = currentState == ToolJournalState.ACKNOWLEDGED
                    ? ToolJournalState.ACKNOWLEDGED.name()
                    : ToolJournalState.DISPATCHED.name();
            ToolJournalRow updated = copy(current, nextState, currentResult(current), evidence, null);
            if (mapper.updateToolJournal(updated, current.state()) != 1) {
                throw new IllegalStateException("concurrent tool journal dispatch conflict");
            }
            return null;
        });
    }

    @Override
    public Optional<ToolDispatchEvidence> dispatchEvidence(AgentRunId runId, RuntimeIdempotencyKey key) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findToolJournal(runId.value(), key.value()))
                .filter(row -> row.dispatchExecutionId() != null)
                .map(this::dispatchEvidence));
    }

    @Override
    public void recordAcknowledged(AgentRunId runId, RuntimeIdempotencyKey key) {
        transition(runId, key, ToolJournalState.ACKNOWLEDGED, null, EnumSet.of(ToolJournalState.DISPATCHED));
    }

    @Override
    public void recordCompleted(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult result) {
        transition(runId, key, ToolJournalState.COMPLETED, result, EnumSet.of(ToolJournalState.PENDING_RESULT));
    }

    @Override
    public void recordPendingResult(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult result) {
        transition(
                runId,
                key,
                ToolJournalState.PENDING_RESULT,
                result,
                EnumSet.of(
                        ToolJournalState.INTENT_RECORDED,
                        ToolJournalState.DISPATCHED,
                        ToolJournalState.ACKNOWLEDGED,
                        ToolJournalState.OUTCOME_UNKNOWN));
    }

    @Override
    public void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey key) {
        transition(
                runId,
                key,
                ToolJournalState.OUTCOME_UNKNOWN,
                null,
                EnumSet.of(ToolJournalState.DISPATCHED, ToolJournalState.ACKNOWLEDGED));
    }

    @Override
    public void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult observedResult) {
        transition(
                runId,
                key,
                ToolJournalState.OUTCOME_UNKNOWN,
                Objects.requireNonNull(observedResult, "observedResult must not be null"),
                EnumSet.of(ToolJournalState.DISPATCHED, ToolJournalState.ACKNOWLEDGED));
    }

    @Override
    public void recordReconciliation(
            AgentRunId runId, RuntimeIdempotencyKey key, ToolReconciliationStatus status, String reasonCode) {
        ToolReconciliationRecord reconciliation = new ToolReconciliationRecord(status, reasonCode);
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            ToolJournalRow current = mapper.findToolJournal(runId.value(), key.value());
            if (current == null) throw new IllegalStateException("tool journal intent does not exist");
            ToolJournalState state = ToolJournalState.valueOf(current.state());
            if (state != ToolJournalState.DISPATCHED
                    && state != ToolJournalState.ACKNOWLEDGED
                    && state != ToolJournalState.OUTCOME_UNKNOWN) {
                throw new IllegalStateException("tool is not in a reconcilable journal state");
            }
            ToolJournalRow updated = copy(current, current.state(), currentResult(current), null, reconciliation);
            if (mapper.updateToolJournal(updated, current.state()) != 1) {
                throw new IllegalStateException("concurrent tool journal reconciliation conflict");
            }
            return null;
        });
    }

    @Override
    public Optional<ToolReconciliationRecord> reconciliation(AgentRunId runId, RuntimeIdempotencyKey key) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findToolJournal(runId.value(), key.value()))
                .filter(row -> row.reconcileStatus() != null)
                .map(row -> new ToolReconciliationRecord(
                        ToolReconciliationStatus.valueOf(row.reconcileStatus()), row.reconcileReason())));
    }

    @Override
    public void recordFailed(AgentRunId runId, RuntimeIdempotencyKey key) {
        transition(
                runId,
                key,
                ToolJournalState.FAILED,
                null,
                EnumSet.of(
                        ToolJournalState.INTENT_RECORDED,
                        ToolJournalState.DISPATCHED,
                        ToolJournalState.ACKNOWLEDGED,
                        ToolJournalState.PENDING_RESULT));
    }

    @Override
    public Optional<ToolJournalState> state(AgentRunId runId, RuntimeIdempotencyKey key) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findToolJournal(runId.value(), key.value()))
                .map(ToolJournalRow::state)
                .map(ToolJournalState::valueOf));
    }

    @Override
    public boolean hasUncertain(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).hasUncertainToolJournal(runId.value()) == 1);
    }

    private Optional<ToolResult> result(AgentRunId runId, RuntimeIdempotencyKey key, ToolJournalState expectedState) {
        return execute(() -> {
            ToolJournalRow row =
                    unitOfWork.mapper(RuntimeStoreMapper.class).findToolJournal(runId.value(), key.value());
            if (row == null || !expectedState.name().equals(row.state())) return Optional.empty();
            if (row.resultPayload() == null) {
                if (expectedState == ToolJournalState.OUTCOME_UNKNOWN) return Optional.empty();
                throw new IllegalStateException("tool journal result payload is missing");
            }
            return Optional.of(decodeResult(row));
        });
    }

    private void transition(
            AgentRunId runId,
            RuntimeIdempotencyKey key,
            ToolJournalState target,
            ToolResult result,
            EnumSet<ToolJournalState> allowedSources) {
        transition(runId, key, target, result, allowedSources, EnumSet.noneOf(ToolJournalState.class));
    }

    private void transition(
            AgentRunId runId,
            RuntimeIdempotencyKey key,
            ToolJournalState target,
            ToolResult result,
            EnumSet<ToolJournalState> allowedSources,
            EnumSet<ToolJournalState> alreadyAdvancedSources) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if ((target == ToolJournalState.PENDING_RESULT || target == ToolJournalState.COMPLETED) && result == null) {
            throw new IllegalArgumentException("result-bearing journal state requires a result");
        }
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            ToolJournalRow current = mapper.findToolJournal(runId.value(), key.value());
            if (current == null) throw new IllegalStateException("tool journal intent does not exist");
            ToolJournalState currentState = ToolJournalState.valueOf(current.state());
            if (currentState == target) {
                if ((result == null && current.resultPayload() == null)
                        || (result != null && decodeResult(current).equals(result))) return null;
                throw new IllegalStateException("tool journal state has conflicting result");
            }
            if (result == null && alreadyAdvancedSources.contains(currentState)) return null;
            if (!allowedSources.contains(currentState)) {
                throw new IllegalStateException("illegal tool journal transition: " + currentState + " -> " + target);
            }
            ToolJournalRow updated = copy(current, target.name(), result, null, null);
            if (mapper.updateToolJournal(updated, current.state()) != 1) {
                throw new IllegalStateException("concurrent tool journal transition conflict");
            }
            return null;
        });
    }

    private ToolResult currentResult(ToolJournalRow row) {
        return row.resultPayload() == null ? null : decodeResult(row);
    }

    private ToolDispatchEvidence dispatchEvidence(ToolJournalRow row) {
        return row.dispatchExecutionId() == null
                ? null
                : new ToolDispatchEvidence(
                        row.dispatchExecutionId(),
                        row.dispatchProcessId() == null
                                ? java.util.OptionalLong.empty()
                                : java.util.OptionalLong.of(row.dispatchProcessId()),
                        row.dispatchWorkdirDigest());
    }

    private ToolJournalRow copy(
            ToolJournalRow current,
            String state,
            ToolResult result,
            ToolDispatchEvidence dispatchEvidence,
            ToolReconciliationRecord reconciliation) {
        EncodedPayload payload = result == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.TOOL_RESULT, ToolResultPayload.from(result));
        ToolDispatchEvidence effectiveDispatch =
                dispatchEvidence != null ? dispatchEvidence : dispatchEvidence(current);
        ToolReconciliationRecord effectiveReconciliation = reconciliation != null
                ? reconciliation
                : current.reconcileStatus() == null
                        ? null
                        : new ToolReconciliationRecord(
                                ToolReconciliationStatus.valueOf(current.reconcileStatus()), current.reconcileReason());
        return new ToolJournalRow(
                current.runId(),
                current.idempotencyKey(),
                state,
                current.toolIdempotency(),
                payload == null ? null : payload.schemaVersion(),
                payload == null ? null : payload.bytes(),
                payload == null ? null : payload.hash(),
                effectiveDispatch == null ? null : effectiveDispatch.executionId(),
                effectiveDispatch == null || effectiveDispatch.processId().isEmpty()
                        ? null
                        : effectiveDispatch.processId().getAsLong(),
                effectiveDispatch == null ? null : effectiveDispatch.workingDirectoryDigest(),
                effectiveReconciliation == null
                        ? null
                        : effectiveReconciliation.status().name(),
                effectiveReconciliation == null ? null : effectiveReconciliation.reasonCode(),
                current.createdAt(),
                Instant.ofEpochMilli(clock.millis()));
    }

    private ToolResult decodeResult(ToolJournalRow row) {
        return codecs.decode(
                        SqliteRuntimePayloadTypes.TOOL_RESULT,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.TOOL_RESULT.name(),
                                row.resultSchemaVersion(),
                                row.resultPayload(),
                                row.resultHash()))
                .toDomain();
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
