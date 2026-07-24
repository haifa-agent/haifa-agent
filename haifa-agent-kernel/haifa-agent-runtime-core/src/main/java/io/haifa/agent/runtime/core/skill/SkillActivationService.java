package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillContent;

public interface SkillActivationService {
    SkillActivation activate(SkillActivationRequest request);

    SkillContent content(SkillActivationRequest request);

    SkillResourceRead readResource(SkillActivationRequest request, String relativePath);
}
