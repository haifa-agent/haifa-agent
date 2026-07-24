package io.haifa.agent.skill.api;

import java.util.Objects;

public record SkillSourceDescriptor(
        SkillSourceRef reference,
        SkillScopeRef scope,
        SkillOrigin origin,
        int priority,
        SkillParserMode parserMode,
        boolean readOnly,
        boolean projectTrustRequired) {
    public SkillSourceDescriptor {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        origin = Objects.requireNonNull(origin, "origin must not be null");
        parserMode = Objects.requireNonNull(parserMode, "parserMode must not be null");
        if (projectTrustRequired && scope.scope() != SkillScope.PROJECT) {
            throw new IllegalArgumentException("projectTrustRequired is only valid for PROJECT sources");
        }
    }
}
