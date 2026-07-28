package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.ExecutionPlatformContribution;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductIsolationAndLifecycleTest {
    private static final ProductContributionCoordinate PROJECT_COORDINATE =
            new ProductContributionCoordinate("project.coding", "1.0");

    @Test
    void personalProfileRejectsCodingCapabilityWhileCodingProfileSelectsIt() {
        ProductContribution project = contribution(
                ProductCapabilities.PROJECT, PROJECT_COORDINATE, new AtomicInteger(), new AtomicInteger(), false);
        var personal = SdkTestFixtures.profile(
                "personal",
                Map.of(ProductCapabilities.PROJECT, ProductCapabilityRequirement.none(ProductCapabilities.PROJECT)));
        var coding = SdkTestFixtures.profile(
                "coding",
                Map.of(
                        ProductCapabilities.PROJECT,
                        ProductCapabilityRequirement.required(
                                ProductCapabilities.PROJECT,
                                Set.of(PROJECT_COORDINATE),
                                ProductProviderSuitability.DEVELOPMENT)));

        var personalContributions = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        personalContributions.add(project);
        assertThatThrownBy(() -> HaifaAgents.builder(personal)
                        .contributeAll(personalContributions)
                        .build())
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("CAPABILITY_FORBIDDEN");

        var codingContributions = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        codingContributions.add(project);
        try (HaifaAgent agent = HaifaAgents.builder(coding)
                .contributeAll(codingContributions)
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build()) {
            assertThat(agent.assembly().contributions()).containsKey(ProductCapabilities.PROJECT);
            assertThat(agent.assembly().profile().allowedTools()).isEmpty();
            assertThat(agent.assembly().profile().allowedSkills()).isEmpty();
        }
    }

    @Test
    void initializesSelectedResourcesAndClosesOnlyOwnedResources() {
        ProductCapabilityId extra = new ProductCapabilityId("lifecycle");
        ProductContributionCoordinate coordinate = new ProductContributionCoordinate("lifecycle.provider", "1.0");
        AtomicInteger successfulInitialize = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        var profile = SdkTestFixtures.profile(
                "lifecycle",
                Map.of(
                        extra,
                        ProductCapabilityRequirement.required(
                                extra, Set.of(coordinate), ProductProviderSuitability.DEVELOPMENT)));
        var contributions = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        contributions.add(contribution(extra, coordinate, successfulInitialize, successfulClose, false));
        HaifaAgent agent = HaifaAgents.builder(profile)
                .contributeAll(contributions)
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build();

        assertThat(successfulInitialize).hasValue(1);
        agent.close();
        agent.close();
        assertThat(successfulClose).hasValue(1);

        AtomicInteger duplicateInitialize = new AtomicInteger();
        AtomicInteger firstFailureClose = new AtomicInteger();
        AtomicInteger secondFailureClose = new AtomicInteger();
        var duplicate = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        duplicate.add(contribution(extra, coordinate, duplicateInitialize, firstFailureClose, false));
        duplicate.add(contribution(extra, coordinate, duplicateInitialize, secondFailureClose, false));

        assertThatThrownBy(() ->
                        HaifaAgents.builder(profile).contributeAll(duplicate).build())
                .isInstanceOf(ProductAssemblyException.class);
        assertThat(duplicateInitialize).hasValue(0);
        assertThat(firstFailureClose).hasValue(0);
        assertThat(secondFailureClose).hasValue(0);

        ProductCapabilityId after = new ProductCapabilityId("lifecycle.z-after");
        ProductContributionCoordinate afterCoordinate = new ProductContributionCoordinate("lifecycle.after", "1.0");
        AtomicInteger beforeInitialize = new AtomicInteger();
        AtomicInteger beforeClose = new AtomicInteger();
        AtomicInteger failedInitialize = new AtomicInteger();
        AtomicInteger failedClose = new AtomicInteger();
        var failingProfile = SdkTestFixtures.profile(
                "lifecycle-failure",
                Map.of(
                        extra,
                        ProductCapabilityRequirement.required(
                                extra, Set.of(coordinate), ProductProviderSuitability.DEVELOPMENT),
                        after,
                        ProductCapabilityRequirement.required(
                                after, Set.of(afterCoordinate), ProductProviderSuitability.DEVELOPMENT)));
        var failing = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        failing.add(contribution(extra, coordinate, beforeInitialize, beforeClose, false));
        failing.add(contribution(after, afterCoordinate, failedInitialize, failedClose, true));

        assertThatThrownBy(() -> HaifaAgents.builder(failingProfile)
                        .contributeAll(failing)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("initialization failed");
        assertThat(beforeInitialize).hasValue(1);
        assertThat(beforeClose).hasValue(1);
        assertThat(failedInitialize).hasValue(1);
        assertThat(failedClose).hasValue(0);
    }

    @Test
    void typedArtifactAndExecutionContributionsRequireExplicitProductPolicies() {
        ProductContributionCoordinate artifactCoordinate = new ProductContributionCoordinate("artifact.memory", "1.0");
        var artifactProfile = SdkTestFixtures.profile(
                "artifact-disabled",
                Map.of(
                        ProductCapabilities.ARTIFACT,
                        ProductCapabilityRequirement.required(
                                ProductCapabilities.ARTIFACT,
                                Set.of(artifactCoordinate),
                                ProductProviderSuitability.DEVELOPMENT)));
        var artifactService = new ArtifactService(
                new InMemoryArtifactStore(),
                new InMemoryArtifactPayloadStore(),
                () -> "artifact-test-id",
                () -> Instant.parse("2026-07-28T00:00:00Z"));
        var artifact = new ArtifactPlatformContribution(
                SdkTestFixtures.metadata(
                        artifactCoordinate,
                        ProductCapabilities.ARTIFACT,
                        SdkConfigurationDigest.sha256("artifact-memory-v1"),
                        ProductProviderSuitability.DEVELOPMENT),
                artifactService);
        var artifactContributions = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        artifactContributions.add(artifact);

        assertThatThrownBy(() -> HaifaAgents.builder(artifactProfile)
                        .contributeAll(artifactContributions)
                        .build())
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("ARTIFACT_POLICY_DISABLED");

        ProductContributionCoordinate executionCoordinate = new ProductContributionCoordinate("execution.host", "1.0");
        var executionProfile = SdkTestFixtures.profile(
                "execution-disabled",
                Map.of(
                        ProductCapabilities.EXECUTION,
                        ProductCapabilityRequirement.required(
                                ProductCapabilities.EXECUTION,
                                Set.of(executionCoordinate),
                                ProductProviderSuitability.DEVELOPMENT)));
        var execution = new ExecutionPlatformContribution(
                SdkTestFixtures.metadata(
                        executionCoordinate,
                        ProductCapabilities.EXECUTION,
                        SdkConfigurationDigest.sha256("execution-host-v1"),
                        ProductProviderSuitability.DEVELOPMENT),
                "host-guard");
        var executionContributions = new java.util.ArrayList<>(SdkTestFixtures.baseContributions());
        executionContributions.add(execution);

        assertThatThrownBy(() -> HaifaAgents.builder(executionProfile)
                        .contributeAll(executionContributions)
                        .build())
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("EXECUTION_POLICY_DISABLED");
    }

    private static ProductContribution contribution(
            ProductCapabilityId capability,
            ProductContributionCoordinate coordinate,
            AtomicInteger initializes,
            AtomicInteger closes,
            boolean failInitialize) {
        return new ProductContribution() {
            @Override
            public ProductContributionCoordinate coordinate() {
                return coordinate;
            }

            @Override
            public ProductCapabilityId capabilityId() {
                return capability;
            }

            @Override
            public String configurationDigest() {
                return SdkConfigurationDigest.sha256(coordinate.externalForm());
            }

            @Override
            public ProductProviderSuitability suitability() {
                return ProductProviderSuitability.DEVELOPMENT;
            }

            @Override
            public String publicSummary() {
                return "safe lifecycle fixture";
            }

            @Override
            public void initialize() {
                initializes.incrementAndGet();
                if (failInitialize) {
                    throw new IllegalStateException("initialization failed");
                }
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }
}
