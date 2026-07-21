package io.haifa.agent.runtime.core.retry;

import java.util.Objects;

/** Typed retry boundary for model calls. */
public record ModelRetryPolicy(RetryPolicy policy) {
    public ModelRetryPolicy {
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public static ModelRetryPolicy none() {
        return new ModelRetryPolicy(RetryPolicy.none());
    }

    @Override
    public RetryPolicy policy() {
        return new RetryPolicy(
                policy.maxAttempts(),
                error -> !(error instanceof io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException)
                        && !(error instanceof io.haifa.agent.model.api.ModelInvocationException modelError
                                && modelError.category()
                                        == io.haifa.agent.model.api.ModelErrorCategory.CONTEXT_TOO_LONG)
                        && policy.retryable().test(error),
                policy.backoff());
    }
}
