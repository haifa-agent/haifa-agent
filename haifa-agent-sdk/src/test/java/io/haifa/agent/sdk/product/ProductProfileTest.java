package io.haifa.agent.sdk.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductProfileTest {
    private static final ProductCapabilityId EXTRA = new ProductCapabilityId("extra");

    @Test
    void digestIsStableAndIncludesStructuredPoliciesWithoutDelimiterCollisions() {
        var firstCoordinate = new ProductContributionCoordinate("a", "b@c");
        var secondCoordinate = new ProductContributionCoordinate("a@b", "c");
        assertThat(firstCoordinate.externalForm()).isEqualTo(secondCoordinate.externalForm());
        assertThat(firstCoordinate).isNotEqualByComparingTo(secondCoordinate);

        ProductProfile first = profile(
                ProductPolicies.safeDefaults(),
                ProductCapabilityRequirement.required(
                        EXTRA, Set.of(firstCoordinate), ProductProviderSuitability.DEVELOPMENT));
        ProductProfile repeated = profile(
                ProductPolicies.safeDefaults(),
                ProductCapabilityRequirement.required(
                        EXTRA, Set.of(firstCoordinate), ProductProviderSuitability.DEVELOPMENT));
        ProductProfile differentCoordinate = profile(
                ProductPolicies.safeDefaults(),
                ProductCapabilityRequirement.required(
                        EXTRA, Set.of(secondCoordinate), ProductProviderSuitability.DEVELOPMENT));
        ProductProfile differentPolicy = profile(
                new ProductPolicies(
                        new ProductMemoryPolicy(true, 32_000, 99),
                        ProductArtifactPolicy.disabled(),
                        ProductExecutionPolicy.disabled()),
                ProductCapabilityRequirement.required(
                        EXTRA, Set.of(firstCoordinate), ProductProviderSuitability.DEVELOPMENT));

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
                Map.of(
                        EXTRA,
                        ProductCapabilityRequirement.required(
                                EXTRA, Set.of(firstCoordinate), ProductProviderSuitability.DEVELOPMENT)),
                Set.of(),
                Set.of(),
                Set.of());
        ProductProfile differentLimits = ProductProfile.create(
                new ProductId("profile-test"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("profile-test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile-test-chat",
                "1.0.0",
                "Safe instructions.",
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000, 128, 64, 16),
                ProductPolicies.safeDefaults(),
                Map.of(
                        EXTRA,
                        ProductCapabilityRequirement.required(
                                EXTRA, Set.of(firstCoordinate), ProductProviderSuitability.DEVELOPMENT)),
                Set.of(),
                Set.of(),
                Set.of());

        assertThat(repeated.configurationDigest()).isEqualTo(first.configurationDigest());
        assertThat(differentCoordinate.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentPolicy.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentQuotaMode.configurationDigest()).isNotEqualTo(first.configurationDigest());
        assertThat(differentLimits.configurationDigest()).isNotEqualTo(first.configurationDigest());
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

    @Test
    void strictEnterpriseProfileDoesNotAssembleExecutionShellOrTerminalCapabilities() {
        ProductCapabilityId terminal = new ProductCapabilityId("terminal");
        ProductProfile enterprise = ProductProfile.create(
                new ProductId("strict-enterprise-sdk"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("strict-enterprise-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "strict-enterprise",
                "1.0.0",
                "Operate only through explicitly contributed business capabilities.",
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                new ProductPolicies(
                        ProductMemoryPolicy.safeDefault(),
                        ProductArtifactPolicy.disabled(),
                        ProductExecutionPolicy.disabled()),
                Map.of(
                        ProductCapabilities.EXECUTION,
                        ProductCapabilityRequirement.none(ProductCapabilities.EXECUTION),
                        ProductCapabilities.SHELL,
                        ProductCapabilityRequirement.none(ProductCapabilities.SHELL),
                        terminal,
                        ProductCapabilityRequirement.none(terminal)),
                Set.of("memory.search"),
                Set.of(),
                Set.of());

        assertThat(enterprise.policies().execution().enabled()).isFalse();
        assertThat(enterprise.requirement(ProductCapabilities.EXECUTION).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(enterprise.requirement(ProductCapabilities.SHELL).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(enterprise.requirement(terminal).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(enterprise.allowedTools()).doesNotContain("execution.run", "execution_run", "shell", "terminal");
    }

    private static ProductProfile profile(ProductPolicies policies, ProductCapabilityRequirement extraRequirement) {
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
                Map.of(EXTRA, extraRequirement),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
