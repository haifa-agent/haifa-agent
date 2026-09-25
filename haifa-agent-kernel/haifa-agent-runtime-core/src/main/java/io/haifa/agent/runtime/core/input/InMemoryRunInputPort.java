package io.haifa.agent.runtime.core.input;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.core.idempotency.CanonicalRequestDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class InMemoryRunInputPort implements RunInputPort {
    private final Map<RunInputId, RunInputRecord> inputs = new HashMap<>();
    private final Map<String, RunInputId> idempotency = new HashMap<>();

    @Override
    public synchronized RunInputAcceptance accept(
            RunInputSubmission submission, String callerScope, Instant acceptedAt) {
        Optional<RunInputRecord> existing = findExisting(submission, callerScope);
        if (existing.isPresent()) return new RunInputAcceptance(existing.orElseThrow(), false);
        RunInputRecord accepted = new RunInputRecord(
                submission,
                RunInputReceiptStatus.ACCEPTED,
                acceptedAt,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
        inputs.put(submission.inputId(), accepted);
        idempotency.put(scope(submission, callerScope), submission.inputId());
        return new RunInputAcceptance(accepted, true);
    }

    @Override
    public synchronized Optional<RunInputRecord> findExisting(RunInputSubmission submission, String callerScope) {
        RunInputId boundId = idempotency.get(scope(submission, callerScope));
        RunInputRecord existing = boundId != null ? inputs.get(boundId) : inputs.get(submission.inputId());
        if (existing == null) return Optional.empty();
        if (!CanonicalRequestDigest.runInputIntent(existing.submission())
                .equals(CanonicalRequestDigest.runInputIntent(submission))) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT,
                    boundId != null
                            ? "The idempotency key is already bound to a different run input"
                            : "The input id is already bound to different content");
        }
        return Optional.of(existing);
    }

    @Override
    public synchronized Optional<RunInputRecord> find(RunInputId inputId) {
        return Optional.ofNullable(inputs.get(inputId));
    }

    @Override
    public synchronized List<RunInputRecord> pending(AgentRunId runId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be in 1..100");
        return inputs.values().stream()
                .filter(input -> input.submission().runId().equals(runId))
                .filter(input -> input.status() == RunInputReceiptStatus.ACCEPTED)
                .sorted(Comparator.comparing(RunInputRecord::acceptedAt)
                        .thenComparing(input -> input.submission().inputId().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized RunInputRecord markApplied(
            RunInputId inputId, String attemptId, int iteration, Instant appliedAt) {
        RunInputRecord current = Optional.ofNullable(inputs.get(inputId))
                .orElseThrow(() -> new IllegalArgumentException("unknown run input"));
        if (current.status() == RunInputReceiptStatus.APPLIED) return current;
        if (current.status() != RunInputReceiptStatus.ACCEPTED) {
            throw new IllegalStateException("only accepted run input can be applied");
        }
        RunInputRecord applied = new RunInputRecord(
                current.submission(),
                RunInputReceiptStatus.APPLIED,
                current.acceptedAt(),
                Optional.of(appliedAt),
                Optional.of(attemptId),
                OptionalInt.of(iteration),
                Optional.empty());
        inputs.put(inputId, applied);
        return applied;
    }

    @Override
    public synchronized RunInputRecord markRejected(RunInputId inputId, String reasonCode) {
        String reason = RunInputReasonCodes.require(reasonCode);
        RunInputRecord current = Optional.ofNullable(inputs.get(inputId))
                .orElseThrow(() -> new IllegalArgumentException("unknown run input"));
        if (current.status() == RunInputReceiptStatus.REJECTED) return current;
        if (current.status() != RunInputReceiptStatus.ACCEPTED) {
            throw new IllegalStateException("only accepted run input can be rejected");
        }
        RunInputRecord rejected = new RunInputRecord(
                current.submission(),
                RunInputReceiptStatus.REJECTED,
                current.acceptedAt(),
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(reason));
        inputs.put(inputId, rejected);
        return rejected;
    }

    private static String scope(RunInputSubmission submission, String callerScope) {
        return Objects.requireNonNull(callerScope, "callerScope must not be null") + "|run-input|"
                + submission.runId().value() + "|" + submission.idempotencyKey();
    }
}
