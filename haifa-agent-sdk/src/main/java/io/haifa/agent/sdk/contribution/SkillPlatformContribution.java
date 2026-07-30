package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import java.util.Objects;

/** Skill catalog and content loader selected by a Product Profile. */
public final class SkillPlatformContribution extends AbstractSdkContribution {
    private final SkillCatalog catalog;
    private final SkillContentLoader contentLoader;
    private final SkillTrustSnapshot trust;

    public SkillPlatformContribution(
            SdkContributionMetadata metadata, SkillCatalog catalog, SkillContentLoader contentLoader) {
        this(metadata, catalog, contentLoader, SkillTrustSnapshot.empty());
    }

    public SkillPlatformContribution(
            SdkContributionMetadata metadata,
            SkillCatalog catalog,
            SkillContentLoader contentLoader,
            SkillTrustSnapshot trust) {
        super(metadata);
        if (!ProductCapabilities.SKILL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("skill contribution must provide the skill capability");
        }
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader must not be null");
        this.trust = Objects.requireNonNull(trust, "trust must not be null");
    }

    public SkillCatalog catalog() {
        return catalog;
    }

    public SkillContentLoader contentLoader() {
        return contentLoader;
    }

    public SkillTrustSnapshot trust() {
        return trust;
    }

    @Override
    public void validate() {
        if (!configurationDigest().equals(catalog.snapshot().digest().value())) {
            throw new IllegalArgumentException("skill contribution digest must match the frozen Skill catalog");
        }
    }
}
