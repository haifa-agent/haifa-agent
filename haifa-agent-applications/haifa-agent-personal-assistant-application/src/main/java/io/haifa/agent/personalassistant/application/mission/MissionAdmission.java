package io.haifa.agent.personalassistant.application.mission;

/** Admission boundary invoked only when a new Mission command has been reserved. */
@FunctionalInterface
public interface MissionAdmission {
    void requireAdmission();

    static MissionAdmission allowAll() {
        return () -> {};
    }
}
