package io.haifa.agent.sdk.product;

/** Frozen product-level limits for direct memory operations. */
public record ProductMemoryPolicy(int maxContentChars, int maxQueryLimit) {

    public ProductMemoryPolicy {
        if (maxContentChars < 1 || maxContentChars > 4_096) {
            throw new IllegalArgumentException("maxContentChars must be between 1 and 4096");
        }
        if (maxQueryLimit < 1 || maxQueryLimit > 1_000) {
            throw new IllegalArgumentException("maxQueryLimit must be between 1 and 1000");
        }
    }

    public static ProductMemoryPolicy safeDefault() {
        return new ProductMemoryPolicy(4_096, 100);
    }
}
