package io.haifa.agent.skill.api;

import java.util.Optional;

public interface SkillContentLoader {
    SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context);

    default void validateBinding(FrozenSkillBinding binding, SkillVisibilityContext context) {
        load(binding, context);
    }

    /**
     * Reads one frozen package resource as raw bytes. Sources that support byte access return the
     * exact frozen bytes; sources that cannot return empty so consumers fail closed.
     */
    default Optional<byte[]> loadResourceBytes(
            FrozenSkillBinding binding, SkillVisibilityContext context, String relativePath) {
        return Optional.empty();
    }

    static SkillContentLoader empty() {
        return (binding, context) -> {
            throw new IllegalStateException("no skill content loader is configured");
        };
    }
}
