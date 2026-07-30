package io.haifa.agent.application.project.product.coding.delivery;

public enum CodingDeliveryEvidenceKind {
    WORKSPACE_CHANGE,
    DIFF_INSPECTION,
    VALIDATION_ATTEMPT,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    READ_ONLY_INSPECTION,
    ARTIFACT_PUBLISHED,
    DOCUMENTATION_CHANGED,
    NO_CHANGE_JUSTIFICATION,
    BLOCKER_CONFIRMED
}
