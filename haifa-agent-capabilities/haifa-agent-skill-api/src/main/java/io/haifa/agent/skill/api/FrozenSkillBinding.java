package io.haifa.agent.skill.api;

import java.util.Objects;

public record FrozenSkillBinding(
        SkillAlias alias,
        SkillCoordinate coordinate,
        SkillMetadata metadata,
        SkillPackageIndex packageIndex,
        SkillContentDigest resourceIndexDigest,
        SkillContentDigest registrationDigest,
        String resolutionPolicyRef) {
    public FrozenSkillBinding {
        alias = Objects.requireNonNull(alias, "alias must not be null");
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        packageIndex = Objects.requireNonNull(packageIndex, "packageIndex must not be null");
        resourceIndexDigest = Objects.requireNonNull(resourceIndexDigest, "resourceIndexDigest must not be null");
        registrationDigest = Objects.requireNonNull(registrationDigest, "registrationDigest must not be null");
        resolutionPolicyRef = SkillValues.text(resolutionPolicyRef, "resolutionPolicyRef", 256);
        if (!metadata.name().equals(coordinate.name())) {
            throw new IllegalArgumentException("frozen metadata and coordinate names differ");
        }
        if (!packageIndex.digest().equals(resourceIndexDigest)
                || !packageIndex.digest().equals(coordinate.contentDigest())) {
            throw new IllegalArgumentException("frozen resource index and content digest differ");
        }
    }
}
