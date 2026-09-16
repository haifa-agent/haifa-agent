package io.haifa.agent.sdk.contribution;

import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import java.util.Objects;

/** Skill catalog and content loader selected by a Product Profile. */
public record SkillPlatformContribution(
        SkillCatalog catalog, SkillContentLoader contentLoader, SkillTrustSnapshot trust) {

    public SkillPlatformContribution {
        catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        contentLoader = Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        trust = Objects.requireNonNull(trust, "trust must not be null");
    }

    public SkillPlatformContribution(SkillCatalog catalog, SkillContentLoader contentLoader) {
        this(catalog, contentLoader, SkillTrustSnapshot.empty());
    }
}
