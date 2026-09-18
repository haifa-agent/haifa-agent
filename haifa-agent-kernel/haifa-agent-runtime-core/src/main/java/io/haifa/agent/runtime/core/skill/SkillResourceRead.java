package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillResourceRef;
import java.util.Objects;

public record SkillResourceRead(FrozenSkillBinding binding, SkillResourceRef resource, String content) {
    public SkillResourceRead {
        binding = Objects.requireNonNull(binding, "binding");
        resource = Objects.requireNonNull(resource, "resource");
        content = Objects.requireNonNull(content, "content");
    }
}
