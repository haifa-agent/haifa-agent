package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record RunInputReceipt(
        RunInputId inputId,
        AgentRunId runId,
        RunInputReceiptStatus status,
        Instant acceptedAt,
        Optional<Instant> appliedAt,
        Optional<String> attemptId,
        OptionalInt iteration,
        Optional<String> reasonCode) {
    public RunInputReceipt {
        inputId = Objects.requireNonNull(inputId, "inputId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null")
                .map(value -> InteractionOption.requireText(value, "attemptId", 256));
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .map(value -> InteractionKind.requireToken(value, "reasonCode"));
        if (iteration.isPresent() && iteration.getAsInt() < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
        if (status == RunInputReceiptStatus.APPLIED
                && (appliedAt.isEmpty() || attemptId.isEmpty() || iteration.isEmpty())) {
            throw new IllegalArgumentException("applied receipt requires application coordinates");
        }
    }
}
