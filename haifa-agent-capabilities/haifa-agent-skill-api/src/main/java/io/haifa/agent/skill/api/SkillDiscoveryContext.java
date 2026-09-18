package io.haifa.agent.skill.api;

import java.util.Objects;

public record SkillDiscoveryContext(SkillVisibilityContext visibility) {
    public SkillDiscoveryContext {
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
    }
}
