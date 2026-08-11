package io.haifa.agent.personalassistant.application.mission;

/** Product-owned dispatch Saga state; it never mirrors the Runtime Run state machine. */
public enum MissionTaskAttemptState {
    CREATED,
    DISPATCH_PENDING,
    BOUND,
    SETTLEMENT_PENDING,
    SETTLED,
    FAILED,
    CANCELLED,
    OUTCOME_UNKNOWN;

    public boolean active() {
        return this == CREATED || this == DISPATCH_PENDING || this == BOUND || this == SETTLEMENT_PENDING;
    }
}
