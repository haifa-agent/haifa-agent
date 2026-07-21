package io.haifa.agent.execution.api;

import java.time.Duration;
import java.util.Objects;

public record ExecutionLimits(Duration timeout, int maxStdoutBytes, int maxStderrBytes, int maxProcesses) {
    public ExecutionLimits {
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("timeout is out of range");
        }
        if (maxStdoutBytes < 1
                || maxStdoutBytes > 16 * 1024 * 1024
                || maxStderrBytes < 1
                || maxStderrBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("output budget is out of range");
        }
        if (maxProcesses < 1 || maxProcesses > 64) throw new IllegalArgumentException("maxProcesses is out of range");
    }
}
