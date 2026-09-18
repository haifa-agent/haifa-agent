package io.haifa.agent.runtime.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

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
                RetryListener.noop(),
                ignored -> policy.maxAttempts());
    }

    public <T> T execute(
            RetryWork<T> work,
            RetryPolicy policy,
            RetryDelayStrategy delays,
            Runnable cancellationCheck,
            RetryListener listener) {
        return execute(work, policy, delays, cancellationCheck, listener, ignored -> policy.maxAttempts());
    }

    public <T> T execute(
            RetryWork<T> work,
            RetryPolicy policy,
            RetryDelayStrategy delays,
            Runnable cancellationCheck,
            RetryListener listener,
            ToIntFunction<RuntimeException> maximumAttempts) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(delays, "delays must not be null");
        Objects.requireNonNull(cancellationCheck, "cancellationCheck must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(maximumAttempts, "maximumAttempts must not be null");
        for (int attempt = 1; ; attempt++) {
            cancellationCheck.run();
            listener.attemptScheduled(attempt);
            try {
                return work.execute(attempt);
            } catch (RuntimeException error) {
                if (!policy.retryable().test(error)) throw error;
                int failureMaximumAttempts = maximumAttempts.applyAsInt(error);
                if (failureMaximumAttempts < 1 || failureMaximumAttempts > policy.maxAttempts()) {
                    throw new IllegalArgumentException(
                            "failure maximum attempts must be between 1 and policy maxAttempts");
                }
                if (attempt >= failureMaximumAttempts) {
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
