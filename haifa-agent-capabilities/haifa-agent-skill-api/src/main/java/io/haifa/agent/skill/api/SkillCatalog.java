package io.haifa.agent.skill.api;

import java.util.Optional;

public interface SkillCatalog {
    Optional<FrozenSkillBinding> findByAlias(SkillAlias alias);

    SkillCatalogSnapshot snapshot();

    static SkillCatalog empty() {
        return new SkillCatalog() {
            @Override
            public Optional<FrozenSkillBinding> findByAlias(SkillAlias alias) {
                return Optional.empty();
            }

            @Override
            public SkillCatalogSnapshot snapshot() {
                return SkillCatalogSnapshot.empty();
            }
        };
    }
}
