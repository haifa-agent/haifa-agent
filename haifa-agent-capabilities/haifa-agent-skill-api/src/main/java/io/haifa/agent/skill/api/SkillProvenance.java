package io.haifa.agent.skill.api;

import java.util.Objects;
import java.util.Optional;

public record SkillProvenance(
        SkillOrigin origin,
        SkillSourceRef source,
        String logicalPackageRef,
        Optional<String> revision,
        Optional<String> license) {
    public SkillProvenance {
        origin = Objects.requireNonNull(origin, "origin must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        logicalPackageRef = SkillValues.text(logicalPackageRef, "logicalPackageRef", 512);
        revision = Objects.requireNonNull(revision, "revision must not be null")
                .map(value -> SkillValues.text(value, "revision", 256));
        license = Objects.requireNonNull(license, "license must not be null")
                .map(value -> SkillValues.text(value, "license", 512));
    }
}
