package io.haifa.agent.personalassistant.application.mission;

public enum MissionTaskState {
    PLANNED,
    WAITING_DEPENDENCY,
    READY,
    COMPLETED,
    BLOCKED,
    CANCELLED
}
