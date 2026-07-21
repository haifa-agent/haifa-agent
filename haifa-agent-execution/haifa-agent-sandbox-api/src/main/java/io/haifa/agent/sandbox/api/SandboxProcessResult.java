package io.haifa.agent.sandbox.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record SandboxProcessResult(
        SandboxProcessStatus status,
        Integer exitCode,
        byte[] stdout,
        byte[] stderr,
        Instant startedAt,
        Instant endedAt,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean processTreeTerminated,
        int observedProcessCount) {
    public SandboxProcessResult {
        status = Objects.requireNonNull(status, "status must not be null");
        stdout = Arrays.copyOf(Objects.requireNonNull(stdout, "stdout must not be null"), stdout.length);
        stderr = Arrays.copyOf(Objects.requireNonNull(stderr, "stderr must not be null"), stderr.length);
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        endedAt = Objects.requireNonNull(endedAt, "endedAt must not be null");
    }

    @Override
    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    @Override
    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }
}
