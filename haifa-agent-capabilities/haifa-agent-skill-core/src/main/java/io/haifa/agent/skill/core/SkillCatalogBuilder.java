package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillDiagnosticSeverity;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillRegistration;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SkillCatalogBuilder {
    private final List<SkillSource> sources;
    private final SkillResolutionPolicy policy;

    public SkillCatalogBuilder(List<SkillSource> sources, SkillResolutionPolicy policy) {
        this.sources = List.copyOf(java.util.Objects.requireNonNull(sources));
        this.policy = java.util.Objects.requireNonNull(policy);
        long distinct = this.sources.stream()
                .map(source -> source.descriptor().reference())
                .distinct()
                .count();
        if (distinct != this.sources.size())
            throw new IllegalArgumentException("Skill source references must be unique");
    }

    public DefaultSkillCatalog build(SkillDiscoveryContext context) {
        List<SkillRegistration> registrations = new ArrayList<>();
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        sources.stream()
                .sorted(Comparator.comparing(source -> source.descriptor().reference()))
                .forEach(source -> {
                    var result = source.discover(context);
                    registrations.addAll(result.registrations());
                    diagnostics.addAll(result.diagnostics());
                });
        Map<SkillAlias, List<SkillRegistration>> groups = new LinkedHashMap<>();
        registrations.stream()
                .filter(registration -> registration.availability() == SkillAvailability.ENABLED)
                .filter(registration ->
                        policy.rank(registration.coordinate().scope().scope()) != Integer.MAX_VALUE)
                .sorted(Comparator.comparing(SkillRegistration::alias).thenComparing(SkillRegistration::coordinate))
                .forEach(registration -> groups.computeIfAbsent(registration.alias(), ignored -> new ArrayList<>())
                        .add(registration));

        List<FrozenSkillBinding> bindings = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<SkillRegistration> candidates = entry.getValue().stream()
                    .sorted(Comparator.comparingInt((SkillRegistration value) ->
                                    policy.rank(value.coordinate().scope().scope()))
                            .thenComparing(Comparator.comparingInt(SkillRegistration::sourcePriority)
                                    .reversed())
                            .thenComparing(SkillRegistration::coordinate))
                    .toList();
            SkillRegistration selected = candidates.getFirst();
            List<SkillRegistration> samePriority = candidates.stream()
                    .filter(candidate -> policy.rank(
                                            candidate.coordinate().scope().scope())
                                    == policy.rank(selected.coordinate().scope().scope())
                            && candidate.sourcePriority() == selected.sourcePriority())
                    .toList();
            long distinctCoordinates = samePriority.stream()
                    .map(SkillRegistration::coordinate)
                    .distinct()
                    .count();
            if (distinctCoordinates > 1) {
                throw new IllegalStateException("ambiguous Skill alias at the same resolution priority: "
                        + entry.getKey().value());
            }
            if (candidates.size() > samePriority.size() && !policy.allowCrossPriorityShadow()) {
                throw new IllegalStateException("Skill shadowing is disabled for alias: "
                        + entry.getKey().value());
            }
            candidates.stream()
                    .skip(samePriority.size())
                    .forEach(shadowed -> diagnostics.add(new SkillDiagnostic(
                            "SKILL_SHADOWED",
                            SkillDiagnosticSeverity.INFO,
                            shadowed.coordinate().source(),
                            Optional.of(shadowed.coordinate().name()),
                            Optional.of(shadowed.provenance().logicalPackageRef()),
                            "a higher-priority Skill registration shadows this candidate")));
            bindings.add(new FrozenSkillBinding(
                    selected.alias(),
                    selected.coordinate(),
                    selected.metadata(),
                    selected.packageIndex(),
                    selected.packageIndex().digest(),
                    selected.registrationDigest(),
                    policy.reference()));
        }
        bindings.sort(Comparator.comparing(FrozenSkillBinding::alias));
        String canonical = policy.reference() + "|"
                + bindings.stream()
                        .map(binding -> binding.alias().value() + "="
                                + binding.coordinate().externalForm() + ":"
                                + binding.registrationDigest().value())
                        .toList();
        SkillCatalogSnapshot snapshot =
                new SkillCatalogSnapshot(SkillDigests.sha256(canonical), policy.reference(), bindings, diagnostics);
        return new DefaultSkillCatalog(snapshot);
    }
}
