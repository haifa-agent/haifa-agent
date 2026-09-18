package io.haifa.agent.skill.api;

import java.util.Objects;
import java.util.Optional;

public record SkillCoordinate(
        SkillScopeRef scope,
        SkillSourceRef source,
        SkillName name,
        Optional<SkillDeclaredVersion> declaredVersion,
        SkillContentDigest contentDigest)
        implements Comparable<SkillCoordinate> {
    public SkillCoordinate {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        name = Objects.requireNonNull(name, "name must not be null");
        declaredVersion = Objects.requireNonNull(declaredVersion, "declaredVersion must not be null");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest must not be null");
    }

    public String externalForm() {
        return scope.externalForm() + "/" + source.externalForm() + "/" + name.value() + "@"
                + declaredVersion.map(SkillDeclaredVersion::value).orElse("unversioned") + "#"
                + contentDigest.value();
    }

    @Override
    public int compareTo(SkillCoordinate other) {
        return externalForm().compareTo(other.externalForm());
    }
}
