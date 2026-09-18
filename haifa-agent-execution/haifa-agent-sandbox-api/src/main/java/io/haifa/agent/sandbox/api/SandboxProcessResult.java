package io.haifa.agent.sandbox.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

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
        int observedProcessCount,
        boolean scratchProvisioned,
        boolean scratchCleanupFailed,
        String failureCode,
        boolean outputIncomplete) {
    public SandboxProcessResult(
            SandboxProcessStatus status,
            Integer exitCode,
            byte[] stdout,
            byte[] stderr,
            Instant startedAt,
            Instant endedAt,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            boolean processTreeTerminated,
            int observedProcessCount,
            boolean scratchProvisioned,
            boolean scratchCleanupFailed) {
        this(
                status,
                exitCode,
                stdout,
                stderr,
                startedAt,
                endedAt,
                stdoutTruncated,
                stderrTruncated,
                processTreeTerminated,
                observedProcessCount,
                scratchProvisioned,
                scratchCleanupFailed,
                null,
                false);
    }

    public SandboxProcessResult(
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
        this(
                status,
                exitCode,
                stdout,
                stderr,
                startedAt,
                endedAt,
                stdoutTruncated,
                stderrTruncated,
                processTreeTerminated,
                observedProcessCount,
                false,
                false,
                null,
                false);
    }

    public SandboxProcessResult {
        status = Objects.requireNonNull(status, "status must not be null");
        stdout = Arrays.copyOf(Objects.requireNonNull(stdout, "stdout must not be null"), stdout.length);
        stderr = Arrays.copyOf(Objects.requireNonNull(stderr, "stderr must not be null"), stderr.length);
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        endedAt = Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (failureCode != null && failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
    }

    @Override
    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    @Override
    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public Optional<String> optionalFailureCode() {
        return Optional.ofNullable(failureCode);
    }
}
