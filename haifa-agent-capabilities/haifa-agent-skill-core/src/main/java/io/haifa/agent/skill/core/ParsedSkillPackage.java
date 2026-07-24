package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillPackageIndex;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ParsedSkillPackage(
        SkillMetadata metadata,
        SkillPackageIndex packageIndex,
        String instructions,
        Map<String, String> readableResources,
        boolean requiresReview,
        List<SkillDiagnostic> diagnostics) {
    public ParsedSkillPackage {
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        packageIndex = Objects.requireNonNull(packageIndex, "packageIndex must not be null");
        instructions = Objects.requireNonNull(instructions, "instructions must not be null");
        readableResources = Map.copyOf(Objects.requireNonNull(readableResources, "readableResources must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
