package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductAssemblyResolverTest {
    private static final ProductCapabilityId EXTRA = new ProductCapabilityId("extra");
    private static final ProductContributionCoordinate EXTRA_COORDINATE =
            new ProductContributionCoordinate("extra.provider", "1.0");

    @Test
    void assemblyDigestIsStableAcrossContributionRegistrationOrder() {
        var profile = SdkTestFixtures.profile(
                "personal",
                Map.of(
                        EXTRA,
                        ProductCapabilityRequirement.required(
                                EXTRA, Set.of(EXTRA_COORDINATE), ProductProviderSuitability.DEVELOPMENT)));
        List<ProductContribution> contributions = new ArrayList<>(SdkTestFixtures.baseContributions());
        contributions.add(contribution(EXTRA, EXTRA_COORDINATE));
        var reversed = new ArrayList<>(contributions);
        java.util.Collections.reverse(reversed);

        var first =
                new ProductAssemblyResolver().resolve(profile, contributions).assembly();
        var second = new ProductAssemblyResolver().resolve(profile, reversed).assembly();

        assertThat(first.assemblyDigest()).isEqualTo(second.assemblyDigest());
        assertThat(first.contributions()).isEqualTo(second.contributions());
    }

    @Test
    void requiredMissingNoneAndAmbiguousCapabilitiesFailClosed() {
        var required = SdkTestFixtures.profile(
                "required",
                Map.of(
                        EXTRA,
                        ProductCapabilityRequirement.required(
                                EXTRA, Set.of(), ProductProviderSuitability.DEVELOPMENT)));
        assertThatThrownBy(() -> new ProductAssemblyResolver().resolve(required, SdkTestFixtures.baseContributions()))
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("CAPABILITY_REQUIRED");

        var none = SdkTestFixtures.profile("none", Map.of());
        var withForbidden = new ArrayList<>(SdkTestFixtures.baseContributions());
        withForbidden.add(contribution(EXTRA, EXTRA_COORDINATE));
        assertThatThrownBy(() -> new ProductAssemblyResolver().resolve(none, withForbidden))
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("CAPABILITY_FORBIDDEN");

        var ambiguousProfile = SdkTestFixtures.profile(
                "ambiguous",
                Map.of(
                        EXTRA,
                        ProductCapabilityRequirement.required(
                                EXTRA, Set.of(), ProductProviderSuitability.DEVELOPMENT)));
        var ambiguous = new ArrayList<>(SdkTestFixtures.baseContributions());
        ambiguous.add(contribution(EXTRA, EXTRA_COORDINATE));
        ambiguous.add(contribution(EXTRA, new ProductContributionCoordinate("extra.alternative", "1.0")));
        assertThatThrownBy(() -> new ProductAssemblyResolver().resolve(ambiguousProfile, ambiguous))
                .isInstanceOf(ProductAssemblyException.class)
                .extracting("code")
                .isEqualTo("CAPABILITY_AMBIGUOUS");
    }

    @Test
    void optionalMissingProducesSafeDiagnostic() {
        var profile = SdkTestFixtures.profile(
                "optional", Map.of(EXTRA, ProductCapabilityRequirement.optional(EXTRA, Set.of())));

        var assembly = new ProductAssemblyResolver()
                .resolve(profile, SdkTestFixtures.baseContributions())
                .assembly();

        assertThat(assembly.diagnostics())
                .singleElement()
                .extracting("code")
                .isEqualTo("CAPABILITY_OPTIONAL_UNAVAILABLE");
    }

    private static ProductContribution contribution(
            ProductCapabilityId capability, ProductContributionCoordinate coordinate) {
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
                return "safe test contribution";
            }
        };
    }
}
