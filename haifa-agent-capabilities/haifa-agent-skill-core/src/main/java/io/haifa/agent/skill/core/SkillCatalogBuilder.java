package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillDiagnosticSeverity;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillRegistration;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.skill.api.SkillTrustSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SkillCatalogBuilder {
    private final List<SkillSource> sources;
    private final SkillResolutionPolicy policy;
    private final SkillTrustSnapshot trust;
    private final Optional<SkillTrustSubject> trustSubject;
    private final Clock clock;

    public SkillCatalogBuilder(List<SkillSource> sources, SkillResolutionPolicy policy) {
        this(sources, policy, SkillTrustSnapshot.empty(), Optional.empty(), Clock.systemUTC());
    }

    public SkillCatalogBuilder(
            List<SkillSource> sources,
            SkillResolutionPolicy policy,
            SkillTrustSnapshot trust,
            SkillTrustSubject trustSubject,
            Clock clock) {
        this(sources, policy, trust, Optional.of(Objects.requireNonNull(trustSubject)), clock);
    }

    private SkillCatalogBuilder(
            List<SkillSource> sources,
            SkillResolutionPolicy policy,
            SkillTrustSnapshot trust,
            Optional<SkillTrustSubject> trustSubject,
            Clock clock) {
        this.sources = List.copyOf(java.util.Objects.requireNonNull(sources));
        this.policy = java.util.Objects.requireNonNull(policy);
        this.trust = Objects.requireNonNull(trust, "trust must not be null");
        this.trustSubject = Objects.requireNonNull(trustSubject, "trustSubject must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        long distinct = this.sources.stream()
                .map(source -> source.descriptor().reference())
                .distinct()
                .count();
        if (distinct != this.sources.size())
            throw new IllegalArgumentException("Skill source references must be unique");
    }

    public DefaultSkillCatalog build(SkillDiscoveryContext context) {
        Instant now = clock.instant();
        List<SkillRegistration> registrations = new ArrayList<>();
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        sources.stream()
                .sorted(Comparator.comparing(source -> source.descriptor().reference()))
                .forEach(source -> {
                    var result = source.discover(context);
                    registrations.addAll(result.registrations());
                    diagnostics.addAll(result.diagnostics());
                });
        Map<SkillRegistration, Optional<SkillPackageReviewGrant>> reviewGrants = new LinkedHashMap<>();
        for (SkillRegistration registration : registrations) {
            Optional<SkillPackageReviewGrant> grant = matchingReviewGrant(registration, now);
            reviewGrants.put(registration, grant);
            if (registration.availability() == SkillAvailability.REVIEW_REQUIRED && grant.isPresent()) {
                diagnostics.add(new SkillDiagnostic(
                        "SKILL_PACKAGE_REVIEW_GRANT_ACCEPTED",
                        SkillDiagnosticSeverity.INFO,
                        registration.coordinate().source(),
                        Optional.of(registration.coordinate().name()),
                        Optional.of(registration.provenance().logicalPackageRef()),
                        "the exact reviewed package is eligible for this catalog"));
            }
        }
        Map<SkillAlias, List<SkillRegistration>> groups = new LinkedHashMap<>();
        registrations.stream()
                .filter(registration -> registration.availability() == SkillAvailability.ENABLED
                        || (registration.availability() == SkillAvailability.REVIEW_REQUIRED
                                && reviewGrants
                                        .getOrDefault(registration, Optional.empty())
                                        .isPresent()))
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
                    policy.reference(),
                    reviewGrants.getOrDefault(selected, Optional.empty()).map(SkillPackageReviewGrant::id)));
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

    private Optional<SkillPackageReviewGrant> matchingReviewGrant(SkillRegistration registration, Instant now) {
        if (registration.availability() != SkillAvailability.REVIEW_REQUIRED || trustSubject.isEmpty()) {
            return Optional.empty();
        }
        List<SkillPackageReviewGrant> matches = trust.packageReviewGrants().stream()
                .filter(grant -> grant.matches(registration, trustSubject.orElseThrow(), now))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("multiple active package review grants match one Skill registration");
        }
        return matches.stream().findFirst();
    }
}
