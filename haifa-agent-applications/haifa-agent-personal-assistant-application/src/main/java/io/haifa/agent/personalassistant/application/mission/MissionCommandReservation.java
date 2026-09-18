package io.haifa.agent.personalassistant.application.mission;

import java.util.Objects;

public record MissionCommandReservation(MissionCommandBinding binding, boolean created) {
    public MissionCommandReservation {
        binding = Objects.requireNonNull(binding, "binding must not be null");
    }
}
