package io.haifa.agent.personalassistant.application.mission;

/** Product-owned dispatch Saga state; it never mirrors the Runtime Run state machine. */
public enum MissionTaskAttemptState {
    DISPATCH_PENDING,
    BOUND,
    SETTLED,
    FAILED,
    CANCELLED,
    OUTCOME_UNKNOWN;

    public boolean active() {
        return this == DISPATCH_PENDING || this == BOUND;
    }
}
