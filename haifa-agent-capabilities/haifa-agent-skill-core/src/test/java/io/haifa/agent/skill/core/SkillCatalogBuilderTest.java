package io.haifa.agent.skill.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillCatalogBuilderTest {
    private final SkillPackageParser parser = new SkillPackageParser(SkillPackageLimits.defaults());
    private final SkillVisibilityContext visibility = new SkillVisibilityContext(
            new TenantRef("tenant"),
            new PrincipalRef("user", "human"),
            Optional.empty(),
            false,
            Set.of(SkillScope.SDK, SkillScope.PRODUCT));

    @Test
    void resolvesHigherScopeAndRecordsShadowingDeterministically() {
        var sdk = source("sdk", SkillScopeRef.sdk(), 0, "# SDK");
        var product = source("product", SkillScopeRef.product(), 0, "# Product");
        var policy = new SkillResolutionPolicy("project-default@1", List.of(SkillScope.PRODUCT, SkillScope.SDK), true);

        var catalog =
                new SkillCatalogBuilder(List.of(sdk, product), policy).build(new SkillDiscoveryContext(visibility));

        assertThat(catalog.snapshot().bindings()).hasSize(1);
        assertThat(catalog.snapshot().bindings().getFirst().coordinate().scope().scope())
                .isEqualTo(SkillScope.PRODUCT);
        assertThat(catalog.snapshot().diagnostics())
                .extracting(value -> value.code())
                .contains("SKILL_SHADOWED");
    }

    @Test
    void failsClosedForSamePriorityCollision() {
        var first = source("one", SkillScopeRef.product(), 0, "# One");
        var second = source("two", SkillScopeRef.product(), 0, "# Two");
        var policy = new SkillResolutionPolicy("strict@1", List.of(SkillScope.PRODUCT), true);

        assertThatThrownBy(() -> new SkillCatalogBuilder(List.of(first, second), policy)
                        .build(new SkillDiscoveryContext(visibility)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous Skill alias");
    }

    @Test
    void catalogDigestDoesNotDependOnSourceEnumerationOrder() {
        var sdk = source("sdk", SkillScopeRef.sdk(), 0, "# SDK");
        var product = source("product", SkillScopeRef.product(), 0, "# Product");
        var policy = new SkillResolutionPolicy("project-default@1", List.of(SkillScope.PRODUCT, SkillScope.SDK), true);
        var first = new SkillCatalogBuilder(List.of(sdk, product), policy).build(new SkillDiscoveryContext(visibility));
        var second =
                new SkillCatalogBuilder(List.of(product, sdk), policy).build(new SkillDiscoveryContext(visibility));

        assertThat(first.snapshot().digest()).isEqualTo(second.snapshot().digest());
    }

    @Test
    void filtersTenantUserAndProjectMetadataBeforeCatalogResolution() {
        var tenantA = new TenantRef("tenant-a");
        var userA = new PrincipalRef("user-a", "human");
        var projectA = new ProjectRef("project-a");
        var context = new SkillDiscoveryContext(new SkillVisibilityContext(
                tenantA,
                userA,
                Optional.of(projectA),
                true,
                Set.of(SkillScope.TENANT, SkillScope.USER, SkillScope.PROJECT)));
        var tenantB = source("tenant-b", SkillScopeRef.tenant(new TenantRef("tenant-b")), 0, "# Tenant B");
        var userB = source("user-b", SkillScopeRef.user(tenantA, new PrincipalRef("user-b", "human")), 0, "# User B");
        var projectB =
                source("project-b", SkillScopeRef.project(tenantA, new ProjectRef("project-b")), 0, "# Project B");
        var project = source("project-a", SkillScopeRef.project(tenantA, projectA), 0, "# Project A");
        var policy = new SkillResolutionPolicy(
                "isolated@1", List.of(SkillScope.USER, SkillScope.PROJECT, SkillScope.TENANT), true);

        var catalog = new SkillCatalogBuilder(List.of(tenantB, userB, projectB, project), policy).build(context);

        assertThat(catalog.snapshot().bindings()).singleElement().satisfies(binding -> assertThat(
                        binding.coordinate().source().sourceId())
                .isEqualTo("project-a"));
        var revoked = new SkillDiscoveryContext(new SkillVisibilityContext(
                tenantA,
                userA,
                Optional.of(projectA),
                false,
                Set.of(SkillScope.TENANT, SkillScope.USER, SkillScope.PROJECT)));
        assertThat(new SkillCatalogBuilder(List.of(project), policy)
                        .build(revoked)
                        .snapshot()
                        .bindings())
                .isEmpty();
    }

    @Test
    void disabledAndScriptReviewRequiredRegistrationsAreNotDisclosed() {
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("review", "1"),
                SkillScopeRef.sdk(),
                SkillOrigin.BUNDLED,
                0,
                SkillParserMode.STRICT,
                true,
                false);
        var review = new InMemorySkillSource(
                descriptor,
                parser,
                SkillAvailability.ENABLED,
                Map.of(
                        "shared-skill",
                        Map.of(
                                "SKILL.md",
                                skillBytes("# Review"),
                                "scripts/run.sh",
                                "echo never".getBytes(StandardCharsets.UTF_8))));
        var disabled = new InMemorySkillSource(
                new SkillSourceDescriptor(
                        new SkillSourceRef("disabled", "1"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        0,
                        SkillParserMode.STRICT,
                        true,
                        false),
                parser,
                SkillAvailability.DISABLED,
                Map.of("shared-skill", Map.of("SKILL.md", skillBytes("# Disabled"))));
        var policy = new SkillResolutionPolicy("enabled-only@1", List.of(SkillScope.PRODUCT, SkillScope.SDK), true);

        assertThat(new SkillCatalogBuilder(List.of(review, disabled), policy)
                        .build(new SkillDiscoveryContext(visibility))
                        .snapshot()
                        .bindings())
                .isEmpty();
    }

    private InMemorySkillSource source(String id, SkillScopeRef scope, int priority, String body) {
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef(id, "1"), scope, SkillOrigin.BUNDLED, priority, SkillParserMode.STRICT, true, false);
        return new InMemorySkillSource(
                descriptor,
                parser,
                SkillAvailability.ENABLED,
                Map.of("shared-skill", Map.of("SKILL.md", skillBytes(body))));
    }

    private static byte[] skillBytes(String body) {
        String skill =
                """
                ---
                name: shared-skill
                description: Provides a shared test method. Use for catalog resolution tests.
                metadata:
                  haifa.version: 1.0.0
                ---
                %s
                """
                        .formatted(body);
        return skill.getBytes(StandardCharsets.UTF_8);
    }
}
