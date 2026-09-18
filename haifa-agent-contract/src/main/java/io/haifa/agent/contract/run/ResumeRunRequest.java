package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.CorrelationId;
import io.haifa.agent.contract.common.IdempotencyKey;
import java.util.Objects;
import java.util.OptionalLong;

/** External resume intent. Trusted caller identity and continuation material are resolved by the adapter. */
public record ResumeRunRequest(String runId, OptionalLong expectedRunVersion, IdempotencyKey idempotencyKey) {
    public ResumeRunRequest {
        runId = CorrelationId.requireText(runId, "runId", 256);
        expectedRunVersion = Objects.requireNonNull(expectedRunVersion, "expectedRunVersion must not be null");
        if (expectedRunVersion.isPresent() && expectedRunVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("expectedRunVersion must not be negative");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }
}
