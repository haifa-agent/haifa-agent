package io.haifa.agent.runtime.core.retry;

import java.util.Objects;

/** Separate retry scope for storage adapters; defaults to no replay of a mutated aggregate. */
public record PersistenceRetryPolicy(RetryPolicy policy) {
    public PersistenceRetryPolicy {
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public static PersistenceRetryPolicy none() {
        return new PersistenceRetryPolicy(RetryPolicy.none());
    }
}
