package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.CorrelationId;
import io.haifa.agent.contract.common.IdempotencyKey;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

public record RuntimeCommandRequest(
        String commandId,
        String runId,
        String commandType,
        OptionalLong expectedRunVersion,
        IdempotencyKey idempotencyKey,
        Instant requestedAt) {
    public RuntimeCommandRequest {
        commandId = CorrelationId.requireText(commandId, "commandId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        commandType = CorrelationId.requireText(commandType, "commandType", 64);
        expectedRunVersion = Objects.requireNonNull(expectedRunVersion, "expectedRunVersion must not be null");
        if (expectedRunVersion.isPresent() && expectedRunVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("expectedRunVersion must not be negative");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }
}
