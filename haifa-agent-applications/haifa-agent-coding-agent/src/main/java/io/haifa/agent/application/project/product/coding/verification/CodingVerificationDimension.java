package io.haifa.agent.application.project.product.coding.verification;

/** Bounded, product-owned dimensions that may be attached to authoritative verification runs. */
public enum CodingVerificationDimension {
    SUCCESS_PATH,
    BOUNDARY,
    FAILURE_PATH,
    FAILURE_ATOMICITY,
    IDEMPOTENCY,
    COMPATIBILITY,
    CONCURRENCY,
    SECURITY_NORMALIZATION,
    RESOURCE_CLEANUP
}
