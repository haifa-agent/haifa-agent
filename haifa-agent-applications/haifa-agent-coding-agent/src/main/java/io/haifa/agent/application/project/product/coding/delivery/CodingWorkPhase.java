package io.haifa.agent.application.project.product.coding.delivery;

/** Derived Coding work phase; it is a projection over authoritative facts, never a Core Run state. */
public enum CodingWorkPhase {
    ORIENT,
    PLAN,
    CHANGE,
    VERIFY,
    REVIEW,
    DELIVER,
    BLOCKED
}
