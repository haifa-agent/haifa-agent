package io.haifa.agent.skill.api;

import java.util.List;
import java.util.Objects;

/** Frozen, safe trust facts supplied by a product's explicit trust manifest. */
public record SkillTrustSnapshot(
        String manifestDigest,
        List<SkillPackageReviewGrant> packageReviewGrants,
        List<SkillScriptExecutionGrant> scriptExecutionGrants) {
    private static final String EMPTY_DIGEST =
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public SkillTrustSnapshot {
        manifestDigest = Objects.requireNonNull(manifestDigest, "manifestDigest must not be null")
                .toLowerCase(java.util.Locale.ROOT);
        if (!manifestDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifestDigest must be a SHA-256 digest");
        }
        packageReviewGrants =
                List.copyOf(Objects.requireNonNull(packageReviewGrants, "packageReviewGrants must not be null"));
        scriptExecutionGrants =
                List.copyOf(Objects.requireNonNull(scriptExecutionGrants, "scriptExecutionGrants must not be null"));
        if (packageReviewGrants.stream()
                        .map(SkillPackageReviewGrant::id)
                        .distinct()
                        .count()
                != packageReviewGrants.size()) {
            throw new IllegalArgumentException("package review grant ids must be unique");
        }
        if (scriptExecutionGrants.stream()
                        .map(SkillScriptExecutionGrant::id)
                        .distinct()
                        .count()
                != scriptExecutionGrants.size()) {
            throw new IllegalArgumentException("script execution grant ids must be unique");
        }
        var packageIds = packageReviewGrants.stream()
                .map(SkillPackageReviewGrant::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (scriptExecutionGrants.stream().anyMatch(grant -> !packageIds.contains(grant.packageReviewGrantId()))) {
            throw new IllegalArgumentException("script execution grant references an unknown package review grant");
        }
    }

    public static SkillTrustSnapshot empty() {
        return new SkillTrustSnapshot(EMPTY_DIGEST, List.of(), List.of());
    }
}
