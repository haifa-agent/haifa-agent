package io.haifa.agent.runtime.core.input;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import io.haifa.agent.runtime.core.idempotency.CanonicalRequestDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class InMemoryRunInputPort implements RunInputPort {
    private record IdempotencyBinding(String requestDigest, RunInputId inputId) {}

    private final Map<RunInputId, RunInputRecord> inputs = new HashMap<>();
    private final Map<String, IdempotencyBinding> idempotency = new HashMap<>();

    @Override
    public synchronized RunInputAcceptance accept(
            RunInputSubmission submission, String callerScope, Instant acceptedAt) {
        String scope = callerScope + "|run-input|" + submission.runId().value() + "|" + submission.idempotencyKey();
        String requestDigest = CanonicalRequestDigest.runInput(submission);
        IdempotencyBinding existingBinding = idempotency.get(scope);
        if (existingBinding != null) {
            if (!existingBinding.requestDigest().equals(requestDigest)) {
                throw new RuntimeContractException(
                        RuntimeErrorCode.IDEMPOTENCY_CONFLICT,
                        "The idempotency key is already bound to a different run input");
            }
            return new RunInputAcceptance(inputs.get(existingBinding.inputId()), false);
        }
        RunInputRecord existingInput = inputs.get(submission.inputId());
        if (existingInput != null) {
            if (!CanonicalRequestDigest.runInput(existingInput.submission()).equals(requestDigest)) {
                throw new RuntimeContractException(
                        RuntimeErrorCode.IDEMPOTENCY_CONFLICT, "The input id is already bound to different content");
            }
            return new RunInputAcceptance(existingInput, false);
        }
        RunInputRecord accepted = new RunInputRecord(
                submission,
                RunInputReceiptStatus.ACCEPTED,
                acceptedAt,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
        inputs.put(submission.inputId(), accepted);
        idempotency.put(scope, new IdempotencyBinding(requestDigest, submission.inputId()));
        return new RunInputAcceptance(accepted, true);
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
}
