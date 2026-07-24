package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.SkillDiagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SkillPackageParseResult(Optional<ParsedSkillPackage> parsed, List<SkillDiagnostic> diagnostics) {
    public SkillPackageParseResult {
        parsed = Objects.requireNonNull(parsed, "parsed must not be null");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
