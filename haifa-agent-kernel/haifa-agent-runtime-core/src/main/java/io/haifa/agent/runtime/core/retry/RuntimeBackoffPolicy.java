package io.haifa.agent.runtime.core.retry;

import java.time.Duration;
import java.util.Objects;

/** Bounded exponential backoff shared by runtime retry policies. */
public final class RuntimeBackoffPolicy implements BackoffStrategy {
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;

    public RuntimeBackoffPolicy(Duration initialDelay, Duration maxDelay, double multiplier) {
        this.initialDelay = requireNonNegative(initialDelay, "initialDelay");
        this.maxDelay = requireNonNegative(maxDelay, "maxDelay");
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than initialDelay");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0d) {
            throw new IllegalArgumentException("multiplier must be finite and at least 1");
        }
        this.multiplier = multiplier;
    }

    @Override
    public Duration delay(int failedAttempt) {
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be positive");
        double scaled = initialDelay.toMillis() * Math.pow(multiplier, failedAttempt - 1L);
        long millis = scaled >= maxDelay.toMillis() ? maxDelay.toMillis() : (long) scaled;
        return Duration.ofMillis(millis);
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }
}
