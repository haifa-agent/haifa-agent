package io.haifa.agent.skill.api;

import io.haifa.agent.tool.api.ToolAlias;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record SkillMetadata(
        SkillName name,
        String description,
        Optional<SkillDeclaredVersion> declaredVersion,
        Optional<String> license,
        Optional<String> compatibility,
        Map<String, String> metadata,
        Set<ToolAlias> toolHints) {
    public SkillMetadata {
        name = Objects.requireNonNull(name, "name must not be null");
        description = SkillValues.text(description, "description", 1024);
        declaredVersion = Objects.requireNonNull(declaredVersion, "declaredVersion must not be null");
        license = Objects.requireNonNull(license, "license must not be null")
                .map(value -> SkillValues.text(value, "license", 512));
        compatibility = Objects.requireNonNull(compatibility, "compatibility must not be null")
                .map(value -> SkillValues.text(value, "compatibility", 500));
        metadata = SkillValues.stringMap(metadata, "metadata");
        toolHints = Objects.requireNonNull(toolHints, "toolHints must not be null").stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toUnmodifiableSet());
    }
}
