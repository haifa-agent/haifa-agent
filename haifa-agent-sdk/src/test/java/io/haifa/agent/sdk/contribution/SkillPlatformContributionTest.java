package io.haifa.agent.sdk.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillScriptExecutionGrant;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustGrantState;
import io.haifa.agent.skill.api.SkillTrustScope;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkillPlatformContributionTest {

    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal-1", "user");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void acceptsEmptyTrustSnapshot() {
        var contribution = new SkillPlatformContribution(
                SkillCatalog.empty(), SkillContentLoader.empty(), SkillTrustSnapshot.empty());

        assertThat(contribution.trust().scriptExecutionGrants()).isEmpty();
    }

    @Test
    void rejectsScriptExecutionGrantsInsteadOfSilentlyIgnoringThem() {
        SkillTrustSnapshot trust = trustWithScriptGrant();

        assertThatThrownBy(() -> new SkillPlatformContribution(SkillCatalog.empty(), SkillContentLoader.empty(), trust))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("script execution grants are not supported");
    }

    private static SkillTrustSnapshot trustWithScriptGrant() {
        SkillContentDigest packageDigest = digest('a');
        SkillCoordinate coordinate = new SkillCoordinate(
                SkillScopeRef.product(),
                new SkillSourceRef("test-source", "1.0.0"),
                new SkillName("demo-skill"),
                Optional.empty(),
                packageDigest);
        SkillPackageReviewGrant packageGrant = new SkillPackageReviewGrant(
                "pkg-grant",
                1,
                1,
                TENANT,
                PRINCIPAL,
                "product-1",
                SkillTrustScope.PRODUCT,
                Optional.empty(),
                coordinate,
                digest('b'),
                packageDigest,
                NOW.minusSeconds(60),
                Optional.empty(),
                Optional.empty(),
                SkillTrustGrantState.ACTIVE,
                "reviewer",
                "test-fixture",
                "SKILL_PACKAGE_REVIEWED");
        SkillScriptExecutionGrant scriptGrant = new SkillScriptExecutionGrant(
                "script-grant",
                1,
                1,
                "pkg-grant",
                TENANT,
                PRINCIPAL,
                "product-1",
                SkillTrustScope.PRODUCT,
                Optional.empty(),
                coordinate,
                digest('c'),
                packageDigest,
                "scripts/run.sh",
                digest('d'),
                new ToolCoordinate(
                        new ToolName("execution_run"),
                        new SemanticVersion("1.0.0"),
                        new ToolProviderId("java.demo"),
                        new ToolDefinitionHash("e".repeat(64))),
                "provider-binding",
                "tool-catalog",
                digest('f').value(),
                "runtime-ref",
                digest('1').value(),
                digest('2').value(),
                List.of("PROCESS_EXECUTION"),
                List.of(),
                NOW.minusSeconds(60),
                Optional.empty(),
                Optional.empty(),
                SkillTrustGrantState.ACTIVE,
                "reviewer",
                "test-fixture",
                "SKILL_SCRIPT_REVIEWED");
        return new SkillTrustSnapshot(digest('3').value(), List.of(packageGrant), List.of(scriptGrant));
    }

    private static SkillContentDigest digest(char value) {
        return new SkillContentDigest("sha256:" + String.valueOf(value).repeat(64));
    }
}
