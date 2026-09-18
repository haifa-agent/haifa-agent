package io.haifa.agent.runtime.core.retry;

import java.time.Duration;

/** Receives sanitized retry lifecycle boundaries; implementations must not persist exception messages blindly. */
public interface RetryListener {
    default void attemptScheduled(int attempt) {}

    default void retryScheduled(int failedAttempt, RuntimeException failure, Duration delay) {}

    default void exhausted(int finalAttempt, RuntimeException failure) {}

    static RetryListener noop() {
        return new RetryListener() {};
    }
}
