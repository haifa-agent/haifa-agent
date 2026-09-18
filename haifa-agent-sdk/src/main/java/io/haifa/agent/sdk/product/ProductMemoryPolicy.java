package io.haifa.agent.sdk.product;

/** Frozen product-level governance for durable memory operations. */
public record ProductMemoryPolicy(boolean manualReviewRequired, int maxCandidateContentChars, int maxQueryLimit) {

    public ProductMemoryPolicy {
        if (!manualReviewRequired) {
            throw new IllegalArgumentException("memory candidates must require manual review");
        }
        if (maxCandidateContentChars < 1 || maxCandidateContentChars > 1_000_000) {
            throw new IllegalArgumentException("maxCandidateContentChars must be between 1 and 1000000");
        }
        if (maxQueryLimit < 1 || maxQueryLimit > 1_000) {
            throw new IllegalArgumentException("maxQueryLimit must be between 1 and 1000");
        }
    }

    public static ProductMemoryPolicy safeDefault() {
        return new ProductMemoryPolicy(true, 32_000, 100);
    }
}
