package io.haifa.agent.personalassistant.server.configuration.health;

import io.haifa.agent.personalassistant.server.mission.MissionOperationsService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Mission-specific readiness kept separate from ordinary conversation availability. */
@Component("personalMission")
public final class PersonalMissionHealth implements HealthIndicator {
    private final MissionOperationsService operations;

    public PersonalMissionHealth(MissionOperationsService operations) {
        this.operations = operations;
    }

    @Override
    public Health health() {
        MissionOperationsService.OperationsSnapshot snapshot = operations.snapshot();
        Health.Builder result =
                snapshot.dispatcher().ready() && snapshot.capacity().acceptingNewWork() ? Health.up() : Health.down();
        return result.withDetail("component", "personal-mission")
                .withDetail("dispatcher", snapshot.dispatcher().status())
                .withDetail("schemaVersion", snapshot.schemaVersion())
                .withDetail("capacity", snapshot.capacity().blockerCode())
                .build();
    }
}
