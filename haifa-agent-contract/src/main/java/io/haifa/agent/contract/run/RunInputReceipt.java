package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record RunInputReceipt(
        ApiVersion apiVersion,
        String inputId,
        String runId,
        String status,
        Instant acceptedAt,
        Optional<Instant> appliedAt,
        Optional<String> attemptId,
        OptionalInt iteration,
        Optional<String> reasonCode) {
    public RunInputReceipt {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        inputId = CorrelationId.requireText(inputId, "inputId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        status = CorrelationId.requireText(status, "status", 64);
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
    }
}
