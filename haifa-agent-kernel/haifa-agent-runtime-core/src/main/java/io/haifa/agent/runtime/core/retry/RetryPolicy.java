package io.haifa.agent.runtime.core.retry;

import java.util.Objects;
import java.util.function.Predicate;

public record RetryPolicy(int maxAttempts, Predicate<RuntimeException> retryable, BackoffStrategy backoff) {
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        retryable = Objects.requireNonNull(retryable, "retryable must not be null");
        backoff = Objects.requireNonNull(backoff, "backoff must not be null");
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, error -> false, BackoffStrategy.none());
    }
}
