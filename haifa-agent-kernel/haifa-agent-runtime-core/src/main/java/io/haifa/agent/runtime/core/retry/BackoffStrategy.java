package io.haifa.agent.runtime.core.retry;

import java.time.Duration;

@FunctionalInterface
public interface BackoffStrategy {
    Duration delay(int failedAttempt);

    static BackoffStrategy none() {
        return attempt -> Duration.ZERO;
    }
}
