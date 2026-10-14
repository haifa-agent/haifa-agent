package io.haifa.agent.skill.api;

import java.util.Optional;

public interface SkillSource {
    SkillSourceDescriptor descriptor();

    SkillDiscoveryResult discover(SkillDiscoveryContext context);

    SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context);

    /**
     * Reads one frozen package resource as raw bytes for trusted execution materialization. Sources
     * that cannot serve bytes return empty and the consumer fails closed.
     */
    default Optional<byte[]> readResource(
            FrozenSkillBinding binding, SkillVisibilityContext context, String relativePath) {
        return Optional.empty();
    }
}
