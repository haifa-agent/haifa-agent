package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.ContentPartDto;
import io.haifa.agent.contract.common.CorrelationId;
import io.haifa.agent.contract.common.IdempotencyKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public record RunInputRequest(
        String inputId,
        String runId,
        OptionalLong expectedRunVersion,
        List<ContentPartDto> contents,
        IdempotencyKey idempotencyKey,
        Instant submittedAt) {
    public RunInputRequest {
        inputId = CorrelationId.requireText(inputId, "inputId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        expectedRunVersion = Objects.requireNonNull(expectedRunVersion, "expectedRunVersion must not be null");
        if (expectedRunVersion.isPresent() && expectedRunVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("expectedRunVersion must not be negative");
        }
        contents = List.copyOf(Objects.requireNonNull(contents, "contents must not be null"));
        if (contents.isEmpty() || contents.size() > 20 || contents.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("contents must contain 1..20 non-null parts");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
    }
}
