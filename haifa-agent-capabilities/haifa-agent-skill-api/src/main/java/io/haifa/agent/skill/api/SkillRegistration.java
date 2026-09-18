package io.haifa.agent.skill.api;

import java.util.List;
import java.util.Objects;

public record SkillRegistration(
        SkillAlias alias,
        SkillCoordinate coordinate,
        SkillOrigin origin,
        SkillMetadata metadata,
        SkillPackageIndex packageIndex,
        SkillProvenance provenance,
        int sourcePriority,
        SkillAvailability availability,
        SkillContentDigest registrationDigest,
        List<SkillDiagnostic> diagnostics) {
    public SkillRegistration {
        alias = Objects.requireNonNull(alias, "alias must not be null");
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        origin = Objects.requireNonNull(origin, "origin must not be null");
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        packageIndex = Objects.requireNonNull(packageIndex, "packageIndex must not be null");
        provenance = Objects.requireNonNull(provenance, "provenance must not be null");
        availability = Objects.requireNonNull(availability, "availability must not be null");
        registrationDigest = Objects.requireNonNull(registrationDigest, "registrationDigest must not be null");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        if (!metadata.name().equals(coordinate.name())) {
            throw new IllegalArgumentException("registration metadata and coordinate names differ");
        }
        if (!packageIndex.digest().equals(coordinate.contentDigest())) {
            throw new IllegalArgumentException("package and coordinate digests differ");
        }
    }
}
