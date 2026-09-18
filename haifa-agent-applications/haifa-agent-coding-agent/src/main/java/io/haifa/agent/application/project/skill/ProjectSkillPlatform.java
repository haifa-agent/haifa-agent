package io.haifa.agent.application.project.skill;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
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
import io.haifa.agent.skill.base.BaseSkills;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ProjectSkillPlatform(SkillCatalog catalog, SkillContentLoader contentLoader) {
    public ProjectSkillPlatform {
        catalog = Objects.requireNonNull(catalog, "catalog");
        contentLoader = Objects.requireNonNull(contentLoader, "contentLoader");
    }

    public static ProjectSkillPlatform baseSkills(
            TenantRef tenant, PrincipalRef principal, Optional<ProjectRef> project, boolean projectTrusted) {
        return baseAndUserDirectorySkills(tenant, principal, project, projectTrusted, List.of());
    }

    public static ProjectSkillPlatform baseAndUserDirectorySkills(
            TenantRef tenant,
            PrincipalRef principal,
            Optional<ProjectRef> project,
            boolean projectTrusted,
            List<UserDirectorySource> userDirectories) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(project, "project");
        List<UserDirectorySource> directories = List.copyOf(Objects.requireNonNull(userDirectories, "userDirectories"));
        if (directories.stream().map(UserDirectorySource::id).distinct().count() != directories.size()) {
            throw new IllegalArgumentException("local Skill source ids must be unique");
        }
        if (directories.stream().map(UserDirectorySource::root).distinct().count() != directories.size()) {
            throw new IllegalArgumentException("local Skill source roots must be unique");
        }

        List<SkillSource> sources = new ArrayList<>();
        sources.add(BaseSkills.source());
        directories.stream()
                .sorted(Comparator.comparing(UserDirectorySource::id))
                .map(directory -> userDirectorySource(tenant, principal, directory))
                .forEach(sources::add);
        var visibility = new SkillVisibilityContext(
                tenant,
                principal,
                project,
                projectTrusted,
                Set.of(SkillScope.USER, SkillScope.PROJECT, SkillScope.TENANT, SkillScope.PRODUCT, SkillScope.SDK));
        var policy = new SkillResolutionPolicy(
                "project-skill-resolution@1",
                List.of(SkillScope.USER, SkillScope.PROJECT, SkillScope.TENANT, SkillScope.PRODUCT, SkillScope.SDK),
                true);
        return new ProjectSkillPlatform(
                new SkillCatalogBuilder(sources, policy).build(new SkillDiscoveryContext(visibility)),
                new CompositeSkillContentLoader(sources));
    }

    private static SkillSource userDirectorySource(
            TenantRef tenant, PrincipalRef principal, UserDirectorySource directory) {
        Path root = trustedRoot(directory);
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("local-directory-" + directory.id(), "1"),
                SkillScopeRef.user(tenant, principal),
                directory.origin(),
                directory.priority(),
                directory.parserMode(),
                true,
                false);
        return new LocalDirectorySkillSource(
                root,
                descriptor,
                new SkillPackageParser(SkillPackageLimits.defaults()),
                io.haifa.agent.skill.api.SkillAvailability.ENABLED);
    }

    private static Path trustedRoot(UserDirectorySource directory) {
        Path root = directory.root();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(root)) {
            throw new IllegalArgumentException("local Skill source is unavailable: " + directory.id());
        }
        try {
            return root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("local Skill source is unavailable: " + directory.id(), exception);
        }
    }

    public record UserDirectorySource(
            String id, Path root, int priority, SkillParserMode parserMode, SkillOrigin origin) {
        public UserDirectorySource {
            id = Objects.requireNonNull(id, "id").trim();
            if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("local Skill source id must be lowercase kebab-case");
            }
            Path configuredRoot = Objects.requireNonNull(root, "root");
            if (!configuredRoot.isAbsolute()) {
                throw new IllegalArgumentException("local Skill source root must be absolute");
            }
            root = configuredRoot.normalize();
            if (priority < 0 || priority > 10_000) {
                throw new IllegalArgumentException("local Skill source priority is out of range");
            }
            parserMode = Objects.requireNonNull(parserMode, "parserMode");
            origin = Objects.requireNonNull(origin, "origin");
            if (origin != SkillOrigin.CREATED && origin != SkillOrigin.IMPORTED) {
                throw new IllegalArgumentException("local Skill source origin must be CREATED or IMPORTED");
            }
        }
    }
}
