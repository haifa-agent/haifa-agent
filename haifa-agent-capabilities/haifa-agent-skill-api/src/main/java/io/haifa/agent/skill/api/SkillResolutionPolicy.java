package io.haifa.agent.skill.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SkillResolutionPolicy(
        String reference, List<SkillScope> scopePrecedence, boolean allowCrossPriorityShadow) {
    public SkillResolutionPolicy {
        reference = SkillValues.text(reference, "reference", 256);
        scopePrecedence = List.copyOf(Objects.requireNonNull(scopePrecedence, "scopePrecedence must not be null"));
        if (scopePrecedence.isEmpty()) throw new IllegalArgumentException("scopePrecedence must not be empty");
        Set<SkillScope> distinct = new LinkedHashSet<>(scopePrecedence);
        if (distinct.size() != scopePrecedence.size()) {
            throw new IllegalArgumentException("scopePrecedence must not contain duplicates");
        }
    }

    public int rank(SkillScope scope) {
        int index = scopePrecedence.indexOf(scope);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
