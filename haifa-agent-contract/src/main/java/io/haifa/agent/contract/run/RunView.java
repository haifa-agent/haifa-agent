package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.ApiVersion;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Transport-neutral snapshot projection. Event cursors are optional until a feed is available. */
public record RunView(
        ApiVersion apiVersion,
        String runId,
        String sessionId,
        String status,
        long version,
        Instant updatedAt,
        Optional<String> output,
        Optional<String> safeErrorCode,
        Optional<AgentExecutionErrorView> error,
        Optional<String> pendingInteractionId,
        Optional<String> baselineCursor,
        Optional<String> headCursor) {
    /**
     * Source-compatible constructor for clients compiled against the original v1 Run view.
     *
     * @deprecated consume {@link #error()} for the complete typed execution failure.
     */
    @Deprecated(forRemoval = true)
    public RunView(
            ApiVersion apiVersion,
            String runId,
            String sessionId,
            String status,
            long version,
            Instant updatedAt,
            Optional<String> output,
            Optional<String> safeErrorCode,
            Optional<String> pendingInteractionId,
            Optional<String> baselineCursor,
            Optional<String> headCursor) {
        this(
                apiVersion,
                runId,
                sessionId,
                status,
                version,
                updatedAt,
                output,
                safeErrorCode,
                Optional.empty(),
                pendingInteractionId,
                baselineCursor,
                headCursor);
    }

    public RunView {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        runId = require(runId, "runId", 256);
        sessionId = require(sessionId, "sessionId", 256);
        status = require(status, "status", 64);
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        output = bounded(output, "output", 65_536);
        safeErrorCode = bounded(safeErrorCode, "safeErrorCode", 128);
        error = Objects.requireNonNull(error, "error must not be null");
        pendingInteractionId = bounded(pendingInteractionId, "pendingInteractionId", 256);
        baselineCursor = bounded(baselineCursor, "baselineCursor", 2_048);
        headCursor = bounded(headCursor, "headCursor", 2_048);
    }

    private static String require(String value, String field, int maximumLength) {
        return io.haifa.agent.contract.common.CorrelationId.requireText(value, field, maximumLength);
    }

    private static Optional<String> bounded(Optional<String> value, String field, int maximumLength) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(item -> require(item, field, maximumLength));
    }
}
