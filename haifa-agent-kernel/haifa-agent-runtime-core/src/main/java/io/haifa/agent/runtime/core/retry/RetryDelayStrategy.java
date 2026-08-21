package io.haifa.agent.runtime.core.retry;

import java.time.Duration;

@FunctionalInterface
public interface RetryDelayStrategy {
    Duration delay(int failedAttempt, RuntimeException failure);
}
