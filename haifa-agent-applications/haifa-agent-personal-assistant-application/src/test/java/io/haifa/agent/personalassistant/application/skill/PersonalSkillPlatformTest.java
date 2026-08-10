package io.haifa.agent.personalassistant.application.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalSkillPlatformTest {
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("personal-user", "user");

    @Test
    void bundlesTheVersionedDeepResearchPackageWithEveryFrozenResource() {
        var platform = PersonalSkillPlatform.create(TENANT, PRINCIPAL, Optional.empty(), List.of());
        var binding = platform.catalog().snapshot().bindings().stream()
                .filter(value -> value.alias().value().equals("deep-research"))
                .findFirst()
                .orElseThrow();
        var content = platform.contentLoader()
                .load(
                        binding,
                        new SkillVisibilityContext(
                                TENANT,
                                PRINCIPAL,
                                Optional.empty(),
                                false,
                                Set.of(SkillScope.PRODUCT, SkillScope.USER)));

        assertThat(binding.coordinate().declaredVersion())
                .hasValueSatisfying(version -> assertThat(version.value()).isEqualTo("2.1.0"));
        assertThat(binding.coordinate().source().sourceId()).isEqualTo("personal-assistant-bundled");
        assertThat(binding.metadata().toolHints())
                .extracting(value -> value.value())
                .containsExactlyInAnyOrder(
                        "web_search", "web_fetch", "utility_wikipedia_search", "utility_wikipedia_summary");
        assertThat(platform.bindingReferences().get("deep-research"))
                .isEqualTo(binding.coordinate().externalForm())
                .contains("product", "personal-assistant-bundled@1", "deep-research@2.1.0")
                .endsWith("#sha256:ccdeb4cad9f75b3b7da3aa221d16673a395b792104e81f07a5e2b4da1d9da532");
        assertThat(content.readableResources())
                .containsKeys(
                        "references/research-types.md",
                        "references/research-method.md",
                        "references/source-quality.md",
                        "references/citation-rules.md",
                        "references/report-quality.md",
                        "schemas/research-task-result-v1.json",
                        "schemas/research-final-result-v1.json",
                        "schemas/research-delivery-v2.json",
                        "templates/report.md");
        assertThat(content.readableResources()).hasSize(9);
        assertThat(content.resource("references/research-types.md"))
                .contains(
                        "TRUTHFULNESS_INVESTIGATION",
                        "DECISION",
                        "POLICY_RISK",
                        "FAILURE_POSTMORTEM",
                        "GENERAL_RESEARCH");
        assertThat(PersonalSkillPlatform.class
                        .getClassLoader()
                        .getResource("skills/deep-research/references/research-types.md"))
                .isNotNull();
        assertThat(platform.load("deep-research", TENANT, PRINCIPAL)).isEqualTo(content);
    }

    @Test
    void researchMethodKeepsFrozenSchemasDatesSourceRolesAndProgressiveDisclosure() {
        var content = PersonalSkillPlatform.create(TENANT, PRINCIPAL, Optional.empty(), List.of())
                .load("deep-research", TENANT, PRINCIPAL);
        String method = content.resource("references/research-method.md");
        String sources = content.resource("references/source-quality.md");

        assertThat(content.instructions())
                .contains(
                        "For a Research Task, read only",
                        "When the Mission requests final synthesis",
                        "never perform new research")
                .doesNotContain("Read `references/report-quality.md`.");
        assertThat(method)
                .contains(
                        "material claims",
                        "DISCOVER",
                        "DEEPEN",
                        "CROSS_CHECK",
                        "Do not refetch a successfully fetched canonical URL",
                        "Once a claim is sufficient",
                        "FINALIZE_ONLY")
                .doesNotMatch("(?s).*\\b20\\d{2}\\b.*")
                .doesNotContain("chain of thought", "Chain of Thought");
        assertThat(sources)
                .contains(
                        "Claim Origin",
                        "Primary Evidence",
                        "Independent Validation",
                        "Counterevidence",
                        "Context",
                        "promotion verified; truth not independently verified");
        assertThat(content.resource("schemas/research-task-result-v1.json"))
                .hasSize(4_208)
                .contains("pa.research-task-result/v1", "DISCOVER", "DEEPEN", "CROSS_CHECK")
                .doesNotContain("sourceRole", "confidence");
        assertThat(content.resource("schemas/research-delivery-v2.json"))
                .hasSize(2_450)
                .contains("pa.research-delivery/v2");
    }

    @Test
    void trustedImportedSourceAcceptsHermesMetadataAndLongerInstructions(@TempDir Path root) throws Exception {
        Path skill = Files.createDirectory(root.resolve("dcf-model"));
        String body = "## Instructions\n" + "Use the verified input values.\n".repeat(700);
        Files.writeString(
                skill.resolve("SKILL.md"),
                """
                ---
                name: dcf-model
                description: Build a discounted cash flow model.
                version: 1.0.0
                platforms: [linux, macos, windows]
                metadata:
                  hermes:
                    tags: [finance, valuation]
                ---
                """
                        + body);

        var platform = PersonalSkillPlatform.create(TENANT, PRINCIPAL, Optional.of(root), List.of());

        assertThat(platform.aliases()).contains("dcf-model");
    }

    @Test
    void discoversTheConfiguredFinanceSkillCollectionWhenOptedIn() {
        String configured = System.getProperty("haifa.financeSkillRoot", "");
        Assumptions.assumeTrue(!configured.isBlank(), "live finance Skill root was not configured");

        var platform = PersonalSkillPlatform.create(TENANT, PRINCIPAL, Optional.of(Path.of(configured)), List.of());

        assertThat(platform.aliases())
                .containsAll(Set.of("3-statement-model", "comps-analysis", "lbo-model", "merger-model", "pptx-author"));
        assertThat(platform.aliases()).doesNotContain("dcf-model", "excel-author", "stocks");
        assertThat(platform.catalog().snapshot().diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().equals("SKILL_SCRIPT_REVIEW_REQUIRED"))
                .extracting(diagnostic -> diagnostic.skill().orElseThrow().value())
                .containsExactlyInAnyOrder("dcf-model", "excel-author", "stocks");
    }
}
