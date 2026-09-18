package io.haifa.agent.runtime.core.input;

import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record RunInputRecord(
        RunInputSubmission submission,
        RunInputReceiptStatus status,
        Instant acceptedAt,
        Optional<Instant> appliedAt,
        Optional<String> attemptId,
        OptionalInt iteration,
        Optional<String> reasonCode) {
    public RunInputRecord {
        submission = Objects.requireNonNull(submission, "submission must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    }

    public RunInputReceipt receipt(RunInputReceiptStatus receiptStatus) {
        return new RunInputReceipt(
                submission.inputId(),
                submission.runId(),
                receiptStatus,
                acceptedAt,
                appliedAt,
                attemptId,
                iteration,
                reasonCode);
    }
}
