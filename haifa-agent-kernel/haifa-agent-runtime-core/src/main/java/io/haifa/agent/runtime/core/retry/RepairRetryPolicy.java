package io.haifa.agent.runtime.core.retry;

public record RepairRetryPolicy(int maxAttempts) {
    public RepairRetryPolicy {
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts must not be negative");
    }

    public void check(int attemptedRepairs) {
        if (attemptedRepairs > maxAttempts) {
            throw new IllegalStateException("repair retry limit exceeded");
        }
    }
}
