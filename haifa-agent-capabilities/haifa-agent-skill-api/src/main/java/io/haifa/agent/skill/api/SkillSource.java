package io.haifa.agent.skill.api;

public interface SkillSource {
    SkillSourceDescriptor descriptor();

    SkillDiscoveryResult discover(SkillDiscoveryContext context);

    SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context);
}
