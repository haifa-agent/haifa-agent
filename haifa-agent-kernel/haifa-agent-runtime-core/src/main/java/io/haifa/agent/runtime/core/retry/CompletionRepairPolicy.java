package io.haifa.agent.runtime.core.retry;

/** Bounded retry budget for product Completion acceptance corrections only. */
public record CompletionRepairPolicy(int maxAttempts) {
    public CompletionRepairPolicy {
        if (maxAttempts < 0) throw new IllegalArgumentException("maxAttempts must not be negative");
    }
}
