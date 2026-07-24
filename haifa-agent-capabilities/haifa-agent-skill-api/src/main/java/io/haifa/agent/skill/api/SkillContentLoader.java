package io.haifa.agent.skill.api;

public interface SkillContentLoader {
    SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context);

    default void validateBinding(FrozenSkillBinding binding, SkillVisibilityContext context) {
        load(binding, context);
    }

    static SkillContentLoader empty() {
        return (binding, context) -> {
            throw new IllegalStateException("no skill content loader is configured");
        };
    }
}
