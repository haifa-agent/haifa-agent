package io.haifa.agent.application.project.product.coding.verification;

public enum CodingVerificationEvidenceKind {
    VERIFICATION_CHECK_STARTED,
    VERIFICATION_CHECK_PASSED,
    VERIFICATION_CHECK_FAILED,
    SIDE_EFFECT_SNAPSHOT,
    ATOMICITY_CONFIRMED,
    IDEMPOTENCY_CONFIRMED,
    COMPATIBILITY_CONFIRMED,
    CONCURRENCY_CHECKED
}
