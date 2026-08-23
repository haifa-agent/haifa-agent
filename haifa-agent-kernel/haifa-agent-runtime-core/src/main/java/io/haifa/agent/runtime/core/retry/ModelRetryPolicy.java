package io.haifa.agent.runtime.core.retry;

import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Typed retry boundary for model calls. */
public record ModelRetryPolicy(
        RetryPolicy policy, Duration maximumDelay, int nonEmptyMaxAttempts, List<Duration> emptyResponseDelays) {
    private static final Duration DEFAULT_MAXIMUM_DELAY = Duration.ofSeconds(30);
    private static final List<Duration> DEFAULT_EMPTY_RESPONSE_DELAYS =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(6));

    public ModelRetryPolicy(RetryPolicy policy) {
        this(policy, DEFAULT_MAXIMUM_DELAY, configuredMaxAttempts(policy), List.of());
    }

    public ModelRetryPolicy(RetryPolicy policy, Duration maximumDelay) {
        this(policy, maximumDelay, configuredMaxAttempts(policy), List.of());
    }

    public ModelRetryPolicy {
        policy = Objects.requireNonNull(policy, "policy must not be null");
        maximumDelay = Objects.requireNonNull(maximumDelay, "maximumDelay must not be null");
        emptyResponseDelays =
                List.copyOf(Objects.requireNonNull(emptyResponseDelays, "emptyResponseDelays must not be null"));
        if (maximumDelay.isNegative()) throw new IllegalArgumentException("maximumDelay must not be negative");
        if (nonEmptyMaxAttempts < 1 || nonEmptyMaxAttempts > policy.maxAttempts()) {
            throw new IllegalArgumentException("nonEmptyMaxAttempts must be between 1 and policy maxAttempts");
        }
        if (!emptyResponseDelays.isEmpty() && emptyResponseDelays.size() < policy.maxAttempts() - 1) {
            throw new IllegalArgumentException("emptyResponseDelays must cover every configured retry");
        }
        if (emptyResponseDelays.stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("emptyResponseDelays must not contain a negative delay");
        }
    }

    public static ModelRetryPolicy none() {
        return new ModelRetryPolicy(RetryPolicy.none());
    }

    public static ModelRetryPolicy defaults() {
        return new ModelRetryPolicy(
                new RetryPolicy(
                        4,
                        ignored -> false,
                        new RuntimeBackoffPolicy(Duration.ofMillis(200), Duration.ofSeconds(5), 2.0d, 0.2d)),
                Duration.ofSeconds(30),
                2,
                DEFAULT_EMPTY_RESPONSE_DELAYS);
    }

    @Override
    public RetryPolicy policy() {
        return new RetryPolicy(policy.maxAttempts(), this::isRetryable, policy.backoff());
    }

    public Duration delay(int failedAttempt, RuntimeException error) {
        if (isEmptyResponse(error) && !emptyResponseDelays.isEmpty()) {
            if (failedAttempt < 1 || failedAttempt > emptyResponseDelays.size()) {
                throw new IllegalArgumentException("empty-response failedAttempt is outside the configured schedule");
            }
            return emptyResponseDelays.get(failedAttempt - 1);
        }
        Duration local =
                Objects.requireNonNull(policy.backoff().delay(failedAttempt), "model retry backoff must not be null");
        if (local.isNegative()) throw new IllegalArgumentException("model retry backoff must not be negative");
        Duration requested = error instanceof ModelInvocationException modelError
                ? modelError.retryAfter().orElse(Duration.ZERO)
                : Duration.ZERO;
        Duration selected = local.compareTo(requested) >= 0 ? local : requested;
        return selected.compareTo(maximumDelay) <= 0 ? selected : maximumDelay;
    }

    public int maxAttempts(RuntimeException error) {
        return isEmptyResponse(error) ? policy.maxAttempts() : nonEmptyMaxAttempts;
    }

    private static int configuredMaxAttempts(RetryPolicy policy) {
        return Objects.requireNonNull(policy, "policy must not be null").maxAttempts();
    }

    private static boolean isEmptyResponse(RuntimeException error) {
        return error instanceof ModelInvocationException modelError
                && modelError.category() == ModelErrorCategory.EMPTY_RESPONSE;
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
