package io.haifa.agent.skill.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record SkillCatalogSnapshot(
        SkillContentDigest digest,
        String resolutionPolicyRef,
        List<FrozenSkillBinding> bindings,
        List<SkillDiagnostic> diagnostics) {
    private static final SkillContentDigest EMPTY_DIGEST =
            new SkillContentDigest("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    public SkillCatalogSnapshot {
        digest = Objects.requireNonNull(digest, "digest must not be null");
        resolutionPolicyRef = SkillValues.text(resolutionPolicyRef, "resolutionPolicyRef", 256);
        bindings = Objects.requireNonNull(bindings, "bindings must not be null").stream()
                .sorted(Comparator.comparing(FrozenSkillBinding::alias))
                .toList();
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        long distinctAliases =
                bindings.stream().map(FrozenSkillBinding::alias).distinct().count();
        if (distinctAliases != bindings.size()) throw new IllegalArgumentException("skill aliases must be unique");
    }

    public static SkillCatalogSnapshot empty() {
        return new SkillCatalogSnapshot(EMPTY_DIGEST, "none@1", List.of(), List.of());
    }
}
