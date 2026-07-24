package io.haifa.agent.skill.base;

import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.core.ClasspathSkillSource;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import java.util.List;

public final class BaseSkills {
    public static final String SOURCE_ID = "classpath:haifa-agent-base-skills";
    public static final String SOURCE_VERSION = "1";
    public static final List<String> NAMES = List.of("result-verification", "task-planning");

    private BaseSkills() {}

    public static SkillSource source() {
        return new ClasspathSkillSource(
                BaseSkills.class.getClassLoader(),
                "META-INF/haifa-agent/skills",
                NAMES,
                new SkillSourceDescriptor(
                        new SkillSourceRef(SOURCE_ID, SOURCE_VERSION),
                        SkillScopeRef.sdk(),
                        SkillOrigin.BUNDLED,
                        0,
                        SkillParserMode.STRICT,
                        true,
                        false),
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
    }
}
