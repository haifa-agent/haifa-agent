package io.haifa.agent.personalassistant.application.skill;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic bundled Skill plus an explicitly configured trusted read-only local source. */
public record PersonalSkillPlatform(SkillCatalog catalog, SkillContentLoader contentLoader, Set<String> aliases) {
    private static final SkillPackageLimits IMPORTED_SKILL_LIMITS =
            new SkillPackageLimits(128, 8, 512 * 1024, 2 * 1024 * 1024, 256 * 1024, 2_000, 20_000);

    public PersonalSkillPlatform {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(contentLoader);
        aliases = Set.copyOf(aliases);
    }

    public static PersonalSkillPlatform create(
            TenantRef tenant, PrincipalRef principal, Optional<Path> configuredLocalRoot, List<Path> forbiddenRoots) {
        List<SkillSource> sources = new ArrayList<>();
        sources.add(bundled());
        configuredLocalRoot.ifPresent(root -> sources.add(local(tenant, principal, root, forbiddenRoots)));
        var visibility = new SkillVisibilityContext(
                tenant, principal, Optional.empty(), false, Set.of(SkillScope.PRODUCT, SkillScope.USER));
        var policy = new SkillResolutionPolicy(
                "personal-skill-resolution@1", List.of(SkillScope.USER, SkillScope.PRODUCT), true);
        var catalog = new SkillCatalogBuilder(sources, policy).build(new SkillDiscoveryContext(visibility));
        Set<String> aliases = catalog.snapshot().bindings().stream()
                .map(binding -> binding.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PersonalSkillPlatform(catalog, new CompositeSkillContentLoader(sources), aliases);
    }

    private static SkillSource bundled() {
        return new ClasspathSkillSource(
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
