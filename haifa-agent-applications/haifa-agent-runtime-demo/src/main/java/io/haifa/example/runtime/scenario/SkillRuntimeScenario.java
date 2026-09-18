package io.haifa.example.runtime.scenario;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.example.runtime.skill.CounterfactualNewsroomSkillPlatform;
import java.util.Optional;
import java.util.Set;

/** Freezes one classpath Skill and activates it through the Runtime {@code skill_load} Tool. */
public final class SkillRuntimeScenario implements RuntimeScenario {
    private static final int MAX_OUTPUT_TOKENS = 3_072;

    private final CounterfactualNewsroomSkillPlatform platform;

    private SkillRuntimeScenario(CounterfactualNewsroomSkillPlatform platform) {
        this.platform = platform;
    }

    public static SkillRuntimeScenario create(RuntimePersistencePorts persistence, TimeProvider time) {
        return new SkillRuntimeScenario(CounterfactualNewsroomSkillPlatform.create(persistence, time));
    }

    @Override
    public String id() {
        return "skill";
    }

    @Override
    public String defaultObjective() {
        return """
               反事实前提：自 1995 年公共互联网商业化开始，底层协议强制所有数据在
               30 天后永久失效，除非一名真实用户主动续期。

               请运行平行世界新闻编辑部，出版 1996、2008、2025 三个年代的中文报纸版面，
               然后审计三个年代之间的因果连续性，并指出最脆弱的因果环节。
               这是紧凑演示：全文不超过 1200 个汉字。
               """
                .strip();
    }

    @Override
    public String instructions() {
        return """
               The run-counterfactual-newsrooms Skill is available as metadata.
               Before writing any news, call skill_load exactly once with
               skill set to run-counterfactual-newsrooms.
               On the next iteration, follow every activated Skill instruction.
               """;
    }

    @Override
    public int maxOutputTokens() {
        return MAX_OUTPUT_TOKENS;
    }

    @Override
    public Set<String> allowedToolAliases() {
        return Set.of(CounterfactualNewsroomSkillPlatform.SKILL_LOAD_ALIAS);
    }

    @Override
    public Set<String> allowedSkillAliases() {
        return Set.of(CounterfactualNewsroomSkillPlatform.SKILL_NAME);
    }

    @Override
    public Optional<DefaultToolCatalog> toolCatalog() {
        return Optional.of(platform.toolCatalog());
    }

    @Override
    public void configure(RuntimeCoreBuilder builder) {
        builder.skillPlatform(platform.skillCatalog(), platform.contentLoader());
    }
}
