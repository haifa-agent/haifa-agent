package io.haifa.agent.orchestration.core.spi;

/** Deterministic durability seam used by crash-window tests and host-level fault injection. */
public enum WorkflowFailurePoint {
    AFTER_RUN_CREATED,
    AFTER_ATTEMPT_SCHEDULED,
    AFTER_AGENT_RUN_ASSOCIATED,
    AFTER_NODE_RESULT_STORED,
    AFTER_CHECKPOINT_STORED,
    AFTER_RESUME_CONSUMED,
    AFTER_CANCEL_COMMITTED
}
