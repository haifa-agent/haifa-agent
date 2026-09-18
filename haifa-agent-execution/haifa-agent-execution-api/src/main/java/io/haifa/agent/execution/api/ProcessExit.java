package io.haifa.agent.execution.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ProcessExit(ExecutionStatus status, Integer exitCode, boolean processTreeTerminated, Instant endedAt) {
    public ProcessExit {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(endedAt, "endedAt");
    }

    public Optional<Integer> optionalExitCode() {
        return Optional.ofNullable(exitCode);
    }
}
