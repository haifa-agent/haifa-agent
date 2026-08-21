package io.haifa.agent.runtime.core.retry;

import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import java.time.Duration;
import java.util.Objects;

/** Typed retry boundary for model calls. */
public record ModelRetryPolicy(RetryPolicy policy, Duration maximumDelay) {
    private static final Duration DEFAULT_MAXIMUM_DELAY = Duration.ofSeconds(30);

    public ModelRetryPolicy(RetryPolicy policy) {
        this(policy, DEFAULT_MAXIMUM_DELAY);
    }

    public ModelRetryPolicy {
        policy = Objects.requireNonNull(policy, "policy must not be null");
        maximumDelay = Objects.requireNonNull(maximumDelay, "maximumDelay must not be null");
        if (maximumDelay.isNegative()) throw new IllegalArgumentException("maximumDelay must not be negative");
    }

    public static ModelRetryPolicy none() {
        return new ModelRetryPolicy(RetryPolicy.none());
    }

    public static ModelRetryPolicy defaults() {
        return new ModelRetryPolicy(
                new RetryPolicy(
                        2,
                        ignored -> false,
                        new RuntimeBackoffPolicy(Duration.ofMillis(200), Duration.ofSeconds(5), 2.0d, 0.2d)),
                Duration.ofSeconds(30));
    }

    @Override
    public RetryPolicy policy() {
        return new RetryPolicy(policy.maxAttempts(), this::isRetryable, policy.backoff());
    }

    public Duration delay(int failedAttempt, RuntimeException error) {
        Duration local =
                Objects.requireNonNull(policy.backoff().delay(failedAttempt), "model retry backoff must not be null");
        if (local.isNegative()) throw new IllegalArgumentException("model retry backoff must not be negative");
        Duration requested = error instanceof ModelInvocationException modelError
                ? modelError.retryAfter().orElse(Duration.ZERO)
                : Duration.ZERO;
        Duration selected = local.compareTo(requested) >= 0 ? local : requested;
        return selected.compareTo(maximumDelay) <= 0 ? selected : maximumDelay;
    }

    private boolean isRetryable(RuntimeException error) {
        if (error instanceof RuntimeLimitExceededException) return false;
        if (!(error instanceof ModelInvocationException modelError))
            return policy.retryable().test(error);
        if (!modelError.retryable() || modelError.outputObserved()) return false;
        return switch (modelError.category()) {
            case EMPTY_RESPONSE, RATE_LIMITED, TIMEOUT, PROVIDER_UNAVAILABLE, SERVER_ERROR, TRANSPORT_ERROR -> true;
            case MALFORMED_RESPONSE -> true;
            case AUTHENTICATION_FAILED,
                    PERMISSION_DENIED,
                    INVALID_REQUEST,
                    MODEL_NOT_FOUND,
                    CONTEXT_TOO_LONG,
                    CONTENT_REJECTED,
                    PARTIAL_RESPONSE,
                    CANCELLED,
                    UNKNOWN_PROVIDER_ERROR -> false;
        };
    }
}
