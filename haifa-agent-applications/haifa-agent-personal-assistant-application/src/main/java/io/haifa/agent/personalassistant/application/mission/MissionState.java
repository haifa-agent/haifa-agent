package io.haifa.agent.personalassistant.application.mission;

public enum MissionState {
    PLANNING,
    WAITING_CONFIRMATION,
    RUNNING,
    WAITING_USER,
    SYNTHESIZING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == PARTIALLY_COMPLETED || this == FAILED || this == CANCELLED;
    }
}
