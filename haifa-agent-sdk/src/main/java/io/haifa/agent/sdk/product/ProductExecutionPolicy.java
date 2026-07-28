package io.haifa.agent.sdk.product;

/** Frozen product-level governance for code and command execution. */
public record ProductExecutionPolicy(
        boolean enabled,
        boolean hostAccessAllowed,
        boolean externalNetworkAllowed,
        int maxParallelExecutions,
        long maxExecutionMillis) {

    public ProductExecutionPolicy {
        if (maxParallelExecutions < 0 || maxExecutionMillis < 0) {
            throw new IllegalArgumentException("execution limits must not be negative");
        }
        if (!enabled
                && (hostAccessAllowed
                        || externalNetworkAllowed
                        || maxParallelExecutions != 0
                        || maxExecutionMillis != 0)) {
            throw new IllegalArgumentException("disabled execution policy must deny access and use zero limits");
        }
        if (enabled && (maxParallelExecutions < 1 || maxExecutionMillis < 1)) {
            throw new IllegalArgumentException("enabled execution policy requires positive limits");
        }
    }

    public static ProductExecutionPolicy disabled() {
        return new ProductExecutionPolicy(false, false, false, 0, 0);
    }
}
