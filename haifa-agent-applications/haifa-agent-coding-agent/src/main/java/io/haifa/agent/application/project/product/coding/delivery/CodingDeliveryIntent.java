package io.haifa.agent.application.project.product.coding.delivery;

/** Frozen upper bound for repository delivery side effects in one Coding Run. */
public enum CodingDeliveryIntent {
    WORKTREE_ONLY,
    LOCAL_COMMIT,
    REMOTE_PUSH,
    PULL_REQUEST;

    public boolean allows(CodingDeliveryIntent required) {
        return ordinal() >= required.ordinal();
    }
}
