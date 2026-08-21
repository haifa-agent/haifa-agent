package io.haifa.agent.runtime.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class RetryExecutor {
    private static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofMillis(100);
    private final Sleeper sleeper;

    public RetryExecutor(Sleeper sleeper) {
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public <T> T execute(Supplier<T> work, RetryPolicy policy) {
        Objects.requireNonNull(work, "work must not be null");
        return execute(
                ignored -> work.get(),
                policy,
                (attempt, ignored) -> policy.backoff().delay(attempt),
                () -> {},
                RetryListener.noop());
    }

    public <T> T execute(
            RetryWork<T> work,
            RetryPolicy policy,
            RetryDelayStrategy delays,
            Runnable cancellationCheck,
            RetryListener listener) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(delays, "delays must not be null");
        Objects.requireNonNull(cancellationCheck, "cancellationCheck must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        for (int attempt = 1; ; attempt++) {
            cancellationCheck.run();
            listener.attemptScheduled(attempt);
            try {
                return work.execute(attempt);
            } catch (RuntimeException error) {
                if (!policy.retryable().test(error)) throw error;
                if (attempt >= policy.maxAttempts()) {
                    listener.exhausted(attempt, error);
                    throw error;
                }
                Duration delay = Objects.requireNonNull(delays.delay(attempt, error), "retry delay must not be null");
                if (delay.isNegative()) throw new IllegalArgumentException("retry delay must not be negative");
                listener.retryScheduled(attempt, error, delay);
                sleep(delay, cancellationCheck);
            }
        }
    }

    private void sleep(Duration delay, Runnable cancellationCheck) {
        Duration remaining = delay;
        while (!remaining.isZero()) {
            cancellationCheck.run();
            Duration slice =
                    remaining.compareTo(CANCELLATION_POLL_INTERVAL) > 0 ? CANCELLATION_POLL_INTERVAL : remaining;
            try {
                sleeper.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellationCheck.run();
                throw new IllegalStateException("retry interrupted", interrupted);
            }
            remaining = remaining.minus(slice);
        }
        cancellationCheck.run();
    }
}
