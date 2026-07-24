package io.haifa.agent.skill.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillDiscoveryResult;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDirectorySkillSourceTest {
    @TempDir
    Path sourceRoot;

    @Test
    void discoversConfiguredProjectSourceLoadsExactContentAndRejectsDriftOrTrustRevocation() throws Exception {
        Path skillRoot = Files.createDirectory(sourceRoot.resolve("project-method"));
        Path entrypoint = skillRoot.resolve("SKILL.md");
        Files.writeString(entrypoint, skill("# Original"));
        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("user", "human");
        var project = new ProjectRef("project");
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("project-source", "1"),
                SkillScopeRef.project(tenant, project),
                SkillOrigin.CREATED,
                0,
                SkillParserMode.STRICT,
                true,
                true);
        var source = new LocalDirectorySkillSource(
                sourceRoot,
                descriptor,
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
        var visible =
                new SkillVisibilityContext(tenant, principal, Optional.of(project), true, Set.of(SkillScope.PROJECT));
        var catalog = new SkillCatalogBuilder(
                        List.of(source), new SkillResolutionPolicy("project-only@1", List.of(SkillScope.PROJECT), true))
                .build(new SkillDiscoveryContext(visible));
        var binding = catalog.snapshot().bindings().getFirst();

        assertThat(source.load(binding, visible).instructions()).contains("# Original");
        var revoked =
                new SkillVisibilityContext(tenant, principal, Optional.of(project), false, Set.of(SkillScope.PROJECT));
        assertThatThrownBy(() -> source.load(binding, revoked)).isInstanceOf(SecurityException.class);

        Files.writeString(entrypoint, skill("# Changed"));
        assertThatThrownBy(() -> source.load(binding, visible))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drifted");
    }

    @Test
    void discoversSkillPackagesBelowCategoryDirectoriesAndPreservesRelativeProvenance() throws Exception {
        Path category = Files.createDirectory(sourceRoot.resolve("creative"));
        Path skillRoot = Files.createDirectory(category.resolve("ascii-art"));
        Files.writeString(
                skillRoot.resolve("SKILL.md"),
                """
                ---
                name: ascii-art
                description: ASCII art banners and custom terminal-safe scenes.
                version: 4.0.0
                metadata:
                  hermes:
                    tags: [ASCII, Art]
                ---
                # ASCII Art

                Create terminal-safe ASCII art.
                """);
        var tenant = new TenantRef("tenant");
        var principal = new PrincipalRef("user", "human");
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("user-source", "1"),
                SkillScopeRef.user(tenant, principal),
                SkillOrigin.IMPORTED,
                100,
                SkillParserMode.COMPATIBLE,
                true,
                false);
        var source = new LocalDirectorySkillSource(
                sourceRoot,
                descriptor,
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED);
        var visible = new SkillVisibilityContext(tenant, principal, Optional.empty(), false, Set.of(SkillScope.USER));

        SkillDiscoveryResult discovered = source.discover(new SkillDiscoveryContext(visible));

        assertThat(discovered.registrations()).singleElement().satisfies(registration -> {
            assertThat(registration.alias().value()).isEqualTo("ascii-art");
            assertThat(registration.provenance().logicalPackageRef()).isEqualTo("creative/ascii-art");
            assertThat(registration.diagnostics())
                    .extracting(diagnostic -> diagnostic.code())
                    .contains("SKILL_UNKNOWN_FRONT_MATTER_FIELD", "SKILL_METADATA_VALUE_IGNORED");
        });
    }

    private static String skill(String body) {
        return """
                ---
                name: project-method
                description: A project-owned method used for exact loader validation.
                ---
                %s
                """
                .formatted(body);
    }
}
