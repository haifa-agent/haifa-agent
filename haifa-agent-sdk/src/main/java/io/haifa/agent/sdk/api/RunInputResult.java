package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Receipt for one steer submission.
 *
 * <p>{@code inputId} correlates the receipt with the {@code run.input.accepted}, {@code run.input.applied} and
 * {@code run.input.rejected} events of the Run. {@code acceptedAt} is empty only when the Run refused the input
 * outright; {@code appliedAt} and {@code iteration} are present exactly for {@link RunInputStatus#APPLIED}, and
 * {@code reasonCode} is a lower-kebab token present exactly for {@link RunInputStatus#REJECTED}.
 */
public record RunInputResult(
        String inputId,
        AgentRunId runId,
        RunInputStatus status,
        Optional<Instant> acceptedAt,
        Optional<Instant> appliedAt,
        OptionalInt iteration,
        Optional<String> reasonCode) {
    /** Reason used when the Run was already completing or terminal when the input arrived. */
    public static final String RUN_NOT_ACCEPTING_INPUT = "run-not-accepting-input";

    public RunInputResult {
        inputId = Objects.requireNonNull(inputId, "inputId must not be null");
        if (inputId.isBlank()) throw new IllegalArgumentException("inputId must not be blank");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        boolean applied = status == RunInputStatus.APPLIED;
        if (applied != (appliedAt.isPresent() && iteration.isPresent())) {
            throw new IllegalArgumentException("application coordinates must be present exactly for APPLIED");
        }
        if ((status == RunInputStatus.REJECTED) != reasonCode.isPresent()) {
            throw new IllegalArgumentException("reasonCode must be present exactly for REJECTED");
        }
        if (acceptedAt.isEmpty() && status != RunInputStatus.REJECTED) {
            throw new IllegalArgumentException("only a refused input may lack acceptedAt");
        }
    }

    static RunInputResult from(io.haifa.agent.runtime.api.RunInputReceipt receipt) {
        RunInputStatus status = RunInputStatus.valueOf(receipt.status().name());
        return new RunInputResult(
                receipt.inputId().value(),
                receipt.runId(),
                status,
                Optional.of(receipt.acceptedAt()),
                status == RunInputStatus.APPLIED ? receipt.appliedAt() : Optional.empty(),
                status == RunInputStatus.APPLIED ? receipt.iteration() : OptionalInt.empty(),
                status == RunInputStatus.REJECTED
                        ? Optional.of(receipt.reasonCode().orElse(RUN_NOT_ACCEPTING_INPUT))
                        : Optional.empty());
    }

    static RunInputResult refused(String inputId, AgentRunId runId) {
        return new RunInputResult(
                inputId,
                runId,
                RunInputStatus.REJECTED,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(RUN_NOT_ACCEPTING_INPUT));
    }
}
