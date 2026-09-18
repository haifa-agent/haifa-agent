package io.haifa.agent.skill.api;

import java.util.Objects;
import java.util.Optional;

public record SkillDiagnostic(
        String code,
        SkillDiagnosticSeverity severity,
        SkillSourceRef source,
        Optional<SkillName> skill,
        Optional<String> logicalPath,
        String message) {
    public SkillDiagnostic {
        code = SkillValues.text(code, "code", 96);
        severity = Objects.requireNonNull(severity, "severity must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        skill = Objects.requireNonNull(skill, "skill must not be null");
        logicalPath = Objects.requireNonNull(logicalPath, "logicalPath must not be null")
                .map(value -> SkillValues.text(value, "logicalPath", 512));
        message = SkillValues.text(message, "message", 512);
    }
}
