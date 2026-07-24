package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultSkillCatalog implements SkillCatalog {
    private final SkillCatalogSnapshot snapshot;
    private final Map<SkillAlias, FrozenSkillBinding> bindings;

    DefaultSkillCatalog(SkillCatalogSnapshot snapshot) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot);
        this.bindings = snapshot.bindings().stream()
                .collect(Collectors.toUnmodifiableMap(FrozenSkillBinding::alias, Function.identity()));
    }

    @Override
    public Optional<FrozenSkillBinding> findByAlias(SkillAlias alias) {
        return Optional.ofNullable(bindings.get(alias));
    }

    @Override
    public SkillCatalogSnapshot snapshot() {
        return snapshot;
    }
}
