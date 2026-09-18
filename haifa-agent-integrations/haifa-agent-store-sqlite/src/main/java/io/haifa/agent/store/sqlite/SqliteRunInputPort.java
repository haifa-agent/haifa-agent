package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.core.idempotency.CanonicalRequestDigest;
import io.haifa.agent.runtime.core.input.RunInputAcceptance;
import io.haifa.agent.runtime.core.input.RunInputPort;
import io.haifa.agent.runtime.core.input.RunInputRecord;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RunInputRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.ContentPartsPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

/** Durable accepted/applied Steer input with caller-scoped idempotency. */
public final class SqliteRunInputPort implements RunInputPort {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteRunInputPort(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public RunInputAcceptance accept(RunInputSubmission submission, String callerScope, Instant acceptedAt) {
        Objects.requireNonNull(submission, "submission must not be null");
        String normalizedScope = requireText(callerScope, "callerScope");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        if (acceptedAt.isBefore(submission.submittedAt())) {
            throw new IllegalArgumentException("acceptedAt must not precede submittedAt");
        }
        String digest = CanonicalRequestDigest.runInput(submission);
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            RunInputRow existing = mapper.findRunInputByIdempotency(
                    normalizedScope, submission.runId().value(), submission.idempotencyKey());
            if (existing != null) return duplicate(existing, digest);
            existing = mapper.findRunInput(submission.inputId().value());
            if (existing != null) return duplicate(existing, digest);
            EncodedPayload contents = codecs.encode(
                    SqliteRuntimePayloadTypes.CONTENT_PARTS, ContentPartsPayload.from(submission.contents()));
            mapper.insertRunInput(new RunInputRow(
                    submission.inputId().value(),
                    submission.runId().value(),
                    submission.expectedRunVersion().isPresent()
                            ? submission.expectedRunVersion().getAsLong()
                            : null,
                    contents.schemaVersion(),
                    contents.bytes(),
                    contents.hash(),
                    normalizedScope,
                    submission.idempotencyKey(),
                    digest,
                    submission.submittedAt(),
                    acceptedAt,
                    RunInputReceiptStatus.ACCEPTED.name(),
                    null,
                    null,
                    null,
                    null));
            return new RunInputAcceptance(
                    new RunInputRecord(
                            submission,
                            RunInputReceiptStatus.ACCEPTED,
                            acceptedAt,
                            Optional.empty(),
                            Optional.empty(),
                            OptionalInt.empty(),
                            Optional.empty()),
                    true);
        });
    }

    @Override
    public Optional<RunInputRecord> find(RunInputId inputId) {
        Objects.requireNonNull(inputId, "inputId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findRunInput(inputId.value()))
                .map(this::fromRow));
    }

    @Override
    public List<RunInputRecord> pending(AgentRunId runId, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be in 1..100");
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).pendingRunInputs(runId.value(), limit).stream()
                .map(this::fromRow)
                .toList());
    }

    @Override
    public RunInputRecord markApplied(RunInputId inputId, String attemptId, int iteration, Instant appliedAt) {
        Objects.requireNonNull(inputId, "inputId must not be null");
        String normalizedAttempt = requireText(attemptId, "attemptId");
        if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            RunInputRow current = mapper.findRunInput(inputId.value());
            if (current == null) throw new IllegalArgumentException("unknown run input");
            if (RunInputReceiptStatus.valueOf(current.status()) == RunInputReceiptStatus.APPLIED) {
                return fromRow(current);
            }
            if (RunInputReceiptStatus.valueOf(current.status()) != RunInputReceiptStatus.ACCEPTED) {
                throw new IllegalStateException("only accepted run input can be applied");
            }
            if (mapper.markRunInputApplied(inputId.value(), normalizedAttempt, iteration, appliedAt) != 1) {
                RunInputRow raced = mapper.findRunInput(inputId.value());
                if (raced == null || RunInputReceiptStatus.valueOf(raced.status()) != RunInputReceiptStatus.APPLIED) {
                    throw new IllegalStateException("run input state changed concurrently");
                }
                return fromRow(raced);
            }
            return fromRow(mapper.findRunInput(inputId.value()));
        });
    }

    private RunInputAcceptance duplicate(RunInputRow existing, String digest) {
        if (!existing.canonicalDigest().equals(digest)) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "The idempotency key or input id is already bound to different content");
        }
        return new RunInputAcceptance(fromRow(existing), false);
    }

    private RunInputRecord fromRow(RunInputRow row) {
        ContentPartsPayload contents = codecs.decode(
                SqliteRuntimePayloadTypes.CONTENT_PARTS,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.CONTENT_PARTS.name(),
                        row.contentsSchemaVersion(),
                        row.contentsPayload(),
                        row.contentsHash()));
        RunInputSubmission submission = new RunInputSubmission(
                new RunInputId(row.inputId()),
                new AgentRunId(row.runId()),
                row.expectedRunVersion() == null ? OptionalLong.empty() : OptionalLong.of(row.expectedRunVersion()),
                contents.toDomain(),
                row.idempotencyKey(),
                row.submittedAt());
        if (!CanonicalRequestDigest.runInput(submission).equals(row.canonicalDigest())) {
            throw new IllegalStateException("run input canonical digest does not match stored content");
        }
        RunInputReceiptStatus status;
        try {
            status = RunInputReceiptStatus.valueOf(row.status());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown run input status", exception);
        }
        return new RunInputRecord(
                submission,
                status,
                row.acceptedAt(),
                Optional.ofNullable(row.appliedAt()),
                Optional.ofNullable(row.attemptId()),
                row.iteration() == null ? OptionalInt.empty() : OptionalInt.of(row.iteration()),
                Optional.ofNullable(row.reasonCode()));
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
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
