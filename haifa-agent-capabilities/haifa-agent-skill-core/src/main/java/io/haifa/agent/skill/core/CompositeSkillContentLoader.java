package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CompositeSkillContentLoader implements SkillContentLoader {
    private final Map<SkillSourceRef, SkillSource> sources;

    public CompositeSkillContentLoader(List<SkillSource> sources) {
        this.sources = List.copyOf(sources).stream()
                .collect(Collectors.toUnmodifiableMap(
                        source -> source.descriptor().reference(), Function.identity()));
    }

    @Override
    public SkillContent load(FrozenSkillBinding binding, SkillVisibilityContext context) {
        SkillSource source = sources.get(binding.coordinate().source());
        if (source == null) throw new IllegalStateException("frozen Skill source adapter is unavailable");
        return source.load(binding, context);
    }
}
