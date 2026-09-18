package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.Objects;

/** Stable keyset cursor value; HTTP encoding remains a Server adapter concern. */
public record MissionListCursor(Instant updatedAt, String missionId) {
    public MissionListCursor {
        updatedAt = MissionValues.millisecond(Objects.requireNonNull(updatedAt), "updatedAt");
        missionId = MissionValues.text(missionId, "missionId", 256);
    }
}
