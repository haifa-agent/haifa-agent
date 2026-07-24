package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillCoordinate;
import java.time.Instant;
import java.util.Objects;

public record SkillCheckpointRef(
        SkillAlias alias, SkillCoordinate coordinate, SkillContentDigest registrationDigest, Instant activatedAt) {
    public SkillCheckpointRef {
        alias = Objects.requireNonNull(alias);
        coordinate = Objects.requireNonNull(coordinate);
        registrationDigest = Objects.requireNonNull(registrationDigest);
        activatedAt = Objects.requireNonNull(activatedAt);
    }
}
