package io.haifa.agent.personalassistant.application.skill;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.skill.api.SkillTrustSubject;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.core.ClasspathSkillSource;
import io.haifa.agent.skill.core.CompositeSkillContentLoader;
import io.haifa.agent.skill.core.LocalDirectorySkillSource;
import io.haifa.agent.skill.core.SkillCatalogBuilder;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic bundled Skill plus an explicitly configured trusted read-only local source. */
public record PersonalSkillPlatform(
        SkillCatalog catalog,
        SkillContentLoader contentLoader,
        Set<String> aliases,
        SkillTrustSnapshot packageTrust,
        PersonalTrustedScriptManifest trustManifest) {
    private static final String PRODUCT_ID = "haifa-personal-assistant";
    private static final SkillPackageLimits IMPORTED_SKILL_LIMITS =
            new SkillPackageLimits(128, 8, 512 * 1024, 2 * 1024 * 1024, 256 * 1024, 2_000, 20_000);

    public PersonalSkillPlatform {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(contentLoader);
        aliases = Set.copyOf(aliases);
        Objects.requireNonNull(packageTrust);
        Objects.requireNonNull(trustManifest);
    }

    /** Stable, fully frozen Skill coordinates keyed by the product-visible alias. */
    public Map<String, String> bindingReferences() {
        return catalog.snapshot().bindings().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        binding -> binding.alias().value(),
                        binding -> binding.coordinate().externalForm()));
    }

    /** Loads one already-selected Product Skill without exposing Skill discovery Tools to the model. */
    public SkillContent load(String alias, TenantRef tenant, PrincipalRef principal) {
        var binding = catalog.snapshot().bindings().stream()
                .filter(candidate -> candidate.alias().value().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Skill alias is unavailable: " + alias));
        var visibility = new SkillVisibilityContext(
                tenant, principal, Optional.empty(), false, Set.of(SkillScope.PRODUCT, SkillScope.USER));
        return contentLoader.load(binding, visibility);
    }

    public static PersonalSkillPlatform create(
            TenantRef tenant, PrincipalRef principal, Optional<Path> configuredLocalRoot, List<Path> forbiddenRoots) {
        return create(
                tenant,
                principal,
                configuredLocalRoot,
                forbiddenRoots,
                PersonalTrustedScriptManifest.empty(),
                Clock.systemUTC());
    }

    public static PersonalSkillPlatform create(
            TenantRef tenant,
            PrincipalRef principal,
            Optional<Path> configuredLocalRoot,
            List<Path> forbiddenRoots,
            PersonalTrustedScriptManifest trustManifest,
            Clock clock) {
        List<SkillSource> sources = new ArrayList<>();
        sources.addAll(bundled());
        configuredLocalRoot.ifPresent(root -> sources.add(local(tenant, principal, root, forbiddenRoots)));
        var visibility = new SkillVisibilityContext(
                tenant, principal, Optional.empty(), false, Set.of(SkillScope.PRODUCT, SkillScope.USER));
        var policy = new SkillResolutionPolicy(
                "personal-skill-resolution@1", List.of(SkillScope.USER, SkillScope.PRODUCT), true);
        List<io.haifa.agent.skill.api.SkillRegistration> registrations = sources.stream()
                .flatMap(source -> source.discover(new SkillDiscoveryContext(visibility)).registrations().stream())
                .toList();
        List<SkillPackageReviewGrant> packageGrants = trustManifest.packages().stream()
                .map(entry -> packageGrant(entry, registrations, tenant, principal))
                .toList();
        SkillTrustSnapshot packageTrust = new SkillTrustSnapshot(trustManifest.digest(), packageGrants, List.of());
        var subject = new SkillTrustSubject(tenant, principal, PRODUCT_ID, Optional.empty());
        var catalog = new SkillCatalogBuilder(sources, policy, packageTrust, subject, clock)
                .build(new SkillDiscoveryContext(visibility));
        Set<String> effectiveGrantIds = catalog.snapshot().bindings().stream()
                .flatMap(binding -> binding.packageReviewGrantId().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!effectiveGrantIds.containsAll(
                packageGrants.stream().map(SkillPackageReviewGrant::id).toList())) {
            throw new IllegalArgumentException("a reviewed Skill package is not effective in the Personal catalog");
        }
        Set<String> aliases = catalog.snapshot().bindings().stream()
                .map(binding -> binding.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PersonalSkillPlatform(
                catalog, new CompositeSkillContentLoader(sources), aliases, packageTrust, trustManifest);
    }

    private static SkillPackageReviewGrant packageGrant(
            PersonalTrustedScriptManifest.PackageReview entry,
            List<io.haifa.agent.skill.api.SkillRegistration> registrations,
            TenantRef tenant,
            PrincipalRef principal) {
        var matches = registrations.stream()
                .filter(registration -> registration.alias().value().equals(entry.skillAlias()))
                .filter(registration -> registration.registrationDigest().equals(entry.registrationContentDigest()))
                .filter(registration -> registration.packageIndex().digest().equals(entry.packageContentDigest()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "package review entry must match exactly one discovered Skill registration");
        }
        var registration = matches.getFirst();
        if (registration.availability() != SkillAvailability.REVIEW_REQUIRED) {
            throw new IllegalArgumentException("package review entry does not identify a review-required Skill");
        }
        return new SkillPackageReviewGrant(
                entry.id(),
                1,
                entry.version(),
                tenant,
                principal,
                PRODUCT_ID,
                entry.scope(),
                Optional.empty(),
                registration.coordinate(),
                registration.registrationDigest(),
                registration.packageIndex().digest(),
                entry.issuedInstant(),
                entry.expiresInstant(),
                entry.revokedInstant(),
                entry.state(),
                entry.reviewerRef(),
                entry.reviewSourceRef(),
                "SKILL_PACKAGE_REVIEWED");
    }

    private static List<SkillSource> bundled() {
        SkillSource standard = new ClasspathSkillSource(
                PersonalSkillPlatform.class.getClassLoader(),
                "META-INF/haifa-agent/personal-skills",
                List.of("daily-planning", "local-script-execution"),
                new SkillSourceDescriptor(
                        new SkillSourceRef("classpath:haifa-personal-skills", "1"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        0,
                        SkillParserMode.STRICT,
                        true,
                        false),
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
        List<String> researchResources = List.of(
                "SKILL.md",
                "references/research-types.md",
                "references/research-method.md",
                "references/source-quality.md",
                "references/citation-rules.md",
                "references/report-quality.md",
                "schemas/research-task-result-v1.json",
                "schemas/research-final-result-v1.json",
                "schemas/research-delivery-v2.json",
                "templates/report.md");
        SkillSource research = new ClasspathSkillSource(
                PersonalSkillPlatform.class.getClassLoader(),
                "skills",
                List.of("deep-research"),
                java.util.Map.of("deep-research", researchResources),
                new SkillSourceDescriptor(
                        new SkillSourceRef("personal-assistant-bundled", "1"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        0,
                        SkillParserMode.STRICT,
                        true,
                        false),
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
        return List.of(standard, research);
    }

    private static SkillSource local(
            TenantRef tenant, PrincipalRef principal, Path configured, List<Path> forbiddenRoots) {
        Path root = trustedRoot(configured);
        for (Path forbidden : forbiddenRoots) {
            Path safeForbidden = absoluteRealOrNormalized(forbidden);
            if (root.startsWith(safeForbidden) || safeForbidden.startsWith(root)) {
                throw new IllegalArgumentException("local Skill source overlaps a protected application path");
            }
        }
        return new LocalDirectorySkillSource(
                root,
                new SkillSourceDescriptor(
                        new SkillSourceRef("local:haifa-personal-skills", "1"),
                        SkillScopeRef.user(tenant, principal),
                        SkillOrigin.IMPORTED,
                        100,
                        SkillParserMode.COMPATIBLE,
                        true,
                        false),
                new SkillPackageParser(IMPORTED_SKILL_LIMITS),
                SkillAvailability.ENABLED);
    }

    private static Path trustedRoot(Path configured) {
        Path normalized = Objects.requireNonNull(configured, "configured must not be null")
                .toAbsolutePath()
                .normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException("local Skill source is unavailable");
        }
        try {
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("local Skill source is unavailable", exception);
        }
    }

    private static Path absoluteRealOrNormalized(Path path) {
        Path normalized = Objects.requireNonNull(path).toAbsolutePath().normalize();
        try {
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return normalized;
        }
    }
}
