package io.haifa.agent.application.project.product.coding.client;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Host-confirmed state of this client's transient Session consent, never an execution permission. */
public record CodingSessionApprovalStatus(State state, Optional<Instant> expiresAt) {
    public enum State {
        ASK,
        PENDING,
        ACTIVE
    }

    public CodingSessionApprovalStatus {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if ((state == State.ASK) != expiresAt.isEmpty()) {
            throw new IllegalArgumentException("Only granted consent has a lease expiry");
        }
    }
}
