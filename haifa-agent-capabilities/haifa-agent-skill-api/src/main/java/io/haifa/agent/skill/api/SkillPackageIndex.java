package io.haifa.agent.skill.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record SkillPackageIndex(SkillContentDigest digest, List<SkillResourceRef> resources) {
    public SkillPackageIndex {
        digest = Objects.requireNonNull(digest, "digest must not be null");
        resources = Objects.requireNonNull(resources, "resources must not be null").stream()
                .sorted(Comparator.comparing(SkillResourceRef::relativePath))
                .toList();
        long distinct = resources.stream()
                .map(SkillResourceRef::relativePath)
                .distinct()
                .count();
        if (distinct != resources.size()) throw new IllegalArgumentException("resource paths must be unique");
        if (resources.stream().noneMatch(resource -> resource.kind() == SkillResourceKind.INSTRUCTION)) {
            throw new IllegalArgumentException("package index must contain SKILL.md");
        }
    }
}
