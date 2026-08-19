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
    public static final String GIT_CLI_SOURCE_ID = "classpath:haifa-agent-git-cli-skills";
    public static final List<String> GIT_CLI_NAMES = List.of("git", "github");

    private BaseSkills() {}

    public static SkillSource source() {
        return source(SOURCE_ID, NAMES);
    }

    public static SkillSource gitCliSource() {
        return source(GIT_CLI_SOURCE_ID, GIT_CLI_NAMES);
    }

    private static SkillSource source(String sourceId, List<String> names) {
        return new ClasspathSkillSource(
                BaseSkills.class.getClassLoader(),
                "META-INF/haifa-agent/skills",
                names,
                new SkillSourceDescriptor(
                        new SkillSourceRef(sourceId, SOURCE_VERSION),
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
