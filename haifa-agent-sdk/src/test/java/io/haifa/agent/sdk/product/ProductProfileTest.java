package io.haifa.agent.sdk.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductProfileTest {

    @Test
    void digestIsStableAndIncludesStructuredPoliciesAndAllowLists() {
        ProductProfile first = profile(ProductPolicies.safeDefaults(), Set.of("memory.search"), Set.of());
        ProductProfile repeated = profile(ProductPolicies.safeDefaults(), Set.of("memory.search"), Set.of());
        ProductProfile differentTools = profile(ProductPolicies.safeDefaults(), Set.of("web_search"), Set.of());
        ProductProfile differentSkills =
                profile(ProductPolicies.safeDefaults(), Set.of("memory.search"), Set.of("plan"));
        ProductProfile differentPolicy = profile(
                new ProductPolicies(
                        new ProductMemoryPolicy(true, 32_000, 99),
                        ProductArtifactPolicy.disabled(),
                        ProductExecutionPolicy.disabled()),
                Set.of("memory.search"),
                Set.of());
        ProductProfile differentQuotaMode = ProductProfile.create(
                new ProductId("profile-test"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("profile-test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile-test-chat",
                "1.0.0",
                "Safe instructions.",
                AgentRunBudget.disabled(),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000, 64, 32, 8),
                ProductPolicies.safeDefaults(),
                Set.of("memory.search"),
                Set.of());

        assertThat(repeated.configurationDigest()).isEqualTo(first.configurationDigest());
        assertThat(differentTools.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentSkills.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentPolicy.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentQuotaMode.configurationDigest()).isNotEqualTo(first.configurationDigest());
    }

    @Test
    void rejectsDigestThatDoesNotMatchFrozenFields() {
        ProductProfile valid = profile(ProductPolicies.safeDefaults(), Set.of(), Set.of());
        assertThatThrownBy(() -> new ProductProfile(
                        valid.schemaVersion(),
                        valid.productId(),
                        valid.productVersion(),
                        valid.definitionId(),
                        valid.definitionVersion(),
                        valid.runProfileId(),
                        valid.runProfileVersion(),
                        valid.instructions(),
                        valid.budget(),
                        valid.limits(),
                        valid.policies(),
                        valid.allowedTools(),
                        valid.allowedSkills(),
                        "sha256:" + "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void policiesFailClosedOnUnsafeMemoryAndDisabledExecutionShapes() {
        assertThatThrownBy(() -> new ProductMemoryPolicy(false, 32_000, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual review");
        assertThatThrownBy(() -> new ProductExecutionPolicy(false, true, false, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled execution");
    }

    private static ProductProfile profile(ProductPolicies policies, Set<String> tools, Set<String> skills) {
        return ProductProfile.create(
                new ProductId("profile-test"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("profile-test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile-test-chat",
                "1.0.0",
                "Safe instructions.",
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                policies,
                tools,
                skills);
    }
}
