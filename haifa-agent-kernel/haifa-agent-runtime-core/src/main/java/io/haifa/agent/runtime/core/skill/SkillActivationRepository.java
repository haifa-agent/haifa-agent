package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillAlias;
import java.util.List;
import java.util.Optional;

public interface SkillActivationRepository {
    SkillActivation saveSkillActivation(
            AgentRunId runId, SkillActivation activation, long maximumInstructionBytes, long maximumEstimatedTokens);

    Optional<SkillActivation> skillActivation(AgentRunId runId, SkillAlias alias);

    List<SkillActivation> skillActivations(AgentRunId runId);

    long addSkillResourceReadBytes(AgentRunId runId, long bytes, long maximum);
}
