package io.haifa.agent.sandbox.api;

import java.time.Duration;
import java.util.Objects;

public record WorkspaceCopyBudget(int maxFiles, long maxFileBytes, long maxTotalBytes, Duration timeout) {
    public WorkspaceCopyBudget {
        if (maxFiles < 1 || maxFileBytes < 1 || maxTotalBytes < 1)
            throw new IllegalArgumentException("budgets must be positive");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }
}
