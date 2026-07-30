package io.haifa.agent.personalassistant.application.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
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
