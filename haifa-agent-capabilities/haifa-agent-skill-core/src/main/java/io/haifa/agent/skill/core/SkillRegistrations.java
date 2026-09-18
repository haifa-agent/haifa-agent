package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillProvenance;
import io.haifa.agent.skill.api.SkillRegistration;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import java.util.Optional;

final class SkillRegistrations {
    private SkillRegistrations() {}

    static SkillRegistration create(
            ParsedSkillPackage parsed,
            SkillSourceDescriptor source,
            String logicalPackageRef,
            SkillAvailability configuredAvailability) {
        SkillAvailability availability =
                parsed.requiresReview() ? SkillAvailability.REVIEW_REQUIRED : configuredAvailability;
        SkillCoordinate coordinate = new SkillCoordinate(
                source.scope(),
                source.reference(),
                parsed.metadata().name(),
                parsed.metadata().declaredVersion(),
                parsed.packageIndex().digest());
        SkillProvenance provenance = new SkillProvenance(
                source.origin(),
                source.reference(),
                logicalPackageRef,
                Optional.of(source.reference().sourceVersion()),
                parsed.metadata().license());
        String canonical =
                coordinate.externalForm() + "|" + source.origin() + "|" + source.priority() + "|" + source.parserMode()
                        + "|" + availability + "|" + parsed.packageIndex().resources();
        return new SkillRegistration(
                new SkillAlias(parsed.metadata().name().value()),
                coordinate,
                source.origin(),
                parsed.metadata(),
                parsed.packageIndex(),
                provenance,
                source.priority(),
                availability,
                SkillDigests.sha256(canonical),
                parsed.diagnostics());
    }
}
