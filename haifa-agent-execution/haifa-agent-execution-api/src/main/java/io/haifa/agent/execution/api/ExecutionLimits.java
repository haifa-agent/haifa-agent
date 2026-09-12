package io.haifa.agent.execution.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ExecutionLimits(
        Duration timeout,
        int maxStdoutBytes,
        int maxStderrBytes,
        Optional<Integer> maxProcesses,
        ExecutionOutputOverflowPolicy outputOverflowPolicy) {
    public ExecutionLimits(Duration timeout, int maxStdoutBytes, int maxStderrBytes) {
        this(timeout, maxStdoutBytes, maxStderrBytes, Optional.empty(), ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL);
    }

    public ExecutionLimits(
            Duration timeout,
            int maxStdoutBytes,
            int maxStderrBytes,
            ExecutionOutputOverflowPolicy outputOverflowPolicy) {
        this(timeout, maxStdoutBytes, maxStderrBytes, Optional.empty(), outputOverflowPolicy);
    }

    public ExecutionLimits(Duration timeout, int maxStdoutBytes, int maxStderrBytes, int maxProcesses) {
        this(
                timeout,
                maxStdoutBytes,
                maxStderrBytes,
                Optional.of(maxProcesses),
                ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL);
    }

    public ExecutionLimits(Duration timeout, int maxStdoutBytes, int maxStderrBytes, Optional<Integer> maxProcesses) {
        this(timeout, maxStdoutBytes, maxStderrBytes, maxProcesses, ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL);
    }

    public ExecutionLimits(
            Duration timeout,
            int maxStdoutBytes,
            int maxStderrBytes,
            int maxProcesses,
            ExecutionOutputOverflowPolicy outputOverflowPolicy) {
        this(timeout, maxStdoutBytes, maxStderrBytes, Optional.of(maxProcesses), outputOverflowPolicy);
    }

    public ExecutionLimits {
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        maxProcesses = Objects.requireNonNull(maxProcesses, "maxProcesses must not be null");
        outputOverflowPolicy = Objects.requireNonNull(outputOverflowPolicy, "outputOverflowPolicy must not be null");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("timeout is out of range");
        }
        if (maxStdoutBytes < 1
                || maxStdoutBytes > 16 * 1024 * 1024
                || maxStderrBytes < 1
                || maxStderrBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("output budget is out of range");
        }
        if (maxProcesses.isPresent()) {
            int processes = maxProcesses.get();
            if (processes < 1 || processes > 64) {
                throw new IllegalArgumentException("maxProcesses is out of range");
            }
        }
    }
}
