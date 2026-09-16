package io.haifa.agent.sdk.contribution;

import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import java.util.Objects;

/**
 * Skill catalog and content loader selected by a Product Profile.
 *
 * <p>The SDK no longer maps {@link SkillTrustSnapshot#scriptExecutionGrants()} to Tool approval. Declaring
 * script execution grants through this component is rejected instead of being silently ignored; a product that
 * needs automatic approval for trusted scripts must express it as an explicit Policy rule.
 */
public record SkillPlatformContribution(
        SkillCatalog catalog, SkillContentLoader contentLoader, SkillTrustSnapshot trust) {

    public SkillPlatformContribution {
        catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        contentLoader = Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        trust = Objects.requireNonNull(trust, "trust must not be null");
        if (!trust.scriptExecutionGrants().isEmpty()) {
            throw new IllegalArgumentException(
                    "script execution grants are not supported; declare an explicit Policy rule instead");
        }
    }

    public SkillPlatformContribution(SkillCatalog catalog, SkillContentLoader contentLoader) {
        this(catalog, contentLoader, SkillTrustSnapshot.empty());
    }
}
