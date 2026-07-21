package io.haifa.agent.runtime.core.retry;

import io.haifa.agent.runtime.core.tool.ToolDefinition;
import java.util.Objects;

/** Prevents automatic replay of side-effecting tools while allowing bounded retries for read-only tools. */
public record ToolRetryPolicy(RetryPolicy policy) {
    public ToolRetryPolicy {
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public RetryPolicy forTool(ToolDefinition definition) {
        if (definition.sideEffecting()) return RetryPolicy.none();
        return new RetryPolicy(
                policy.maxAttempts(),
                error -> !(error instanceof io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException)
                        && policy.retryable().test(error),
                policy.backoff());
    }

    public static ToolRetryPolicy none() {
        return new ToolRetryPolicy(RetryPolicy.none());
    }
}
