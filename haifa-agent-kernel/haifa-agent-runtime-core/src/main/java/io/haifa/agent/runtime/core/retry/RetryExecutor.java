package io.haifa.agent.runtime.core.retry;

import java.util.Objects;
import java.util.function.Supplier;

public final class RetryExecutor {
    private final Sleeper sleeper;

    public RetryExecutor(Sleeper sleeper) {
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public <T> T execute(Supplier<T> work, RetryPolicy policy) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException error) {
                if (attempt >= policy.maxAttempts() || !policy.retryable().test(error)) throw error;
                try {
                    sleeper.sleep(policy.backoff().delay(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("retry interrupted", interrupted);
                }
            }
        }
    }
}
