package io.haifa.agent.model.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderHealth;
import io.haifa.agent.model.api.ProviderHealthStatus;
import io.haifa.agent.model.api.ProviderStatus;
import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCoreTest {
    @Test
    void selectsExactActiveModelAndProducesStableSnapshot() {
        ModelProviderDefinition provider = provider(ProviderStatus.ACTIVE, ModelStatus.ACTIVE);
        DeterministicModelSelector selector = new DeterministicModelSelector(
                new ImmutableModelCatalog(List.of(provider)), ModelAccessPolicy.allowAll(), "1.0.0");
        ModelSelectionRequest request = request(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING));

        var first = selector.select(request);
        var second = selector.select(request);

        assertThat(first.providerId().value()).isEqualTo("deepseek");
        assertThat(first.providerModelId()).isEqualTo("deepseek-v4-pro");
        assertThat(first.configurationDigest())
                .isEqualTo(second.configurationDigest())
                .startsWith("sha256:");
    }

    @Test
    void refusesDisabledAndCapabilityMismatchedModelsWithoutFallback() {
        DeterministicModelSelector disabled = new DeterministicModelSelector(
                new ImmutableModelCatalog(List.of(provider(ProviderStatus.ACTIVE, ModelStatus.DISABLED))),
                ModelAccessPolicy.allowAll(),
                "1.0.0");
        assertThatThrownBy(() -> disabled.select(request(Set.of(ModelCapability.TEXT_CHAT))))
                .isInstanceOf(ModelSelectionException.class)
                .extracting(value -> ((ModelSelectionException) value).failure())
                .isEqualTo(ModelSelectionFailure.MODEL_NOT_ACTIVE);

        DeterministicModelSelector mismatch = new DeterministicModelSelector(
                new ImmutableModelCatalog(List.of(provider(ProviderStatus.ACTIVE, ModelStatus.ACTIVE))),
                ModelAccessPolicy.allowAll(),
                "1.0.0");
        assertThatThrownBy(() -> mismatch.select(request(Set.of(ModelCapability.STRUCTURED_OUTPUT))))
                .isInstanceOf(ModelSelectionException.class)
                .extracting(value -> ((ModelSelectionException) value).failure())
                .isEqualTo(ModelSelectionFailure.CAPABILITY_MISMATCH);
    }

    @Test
    void rejectsDuplicateGlobalModelIdsAndAccessDenial() {
        ModelProviderDefinition first = provider(ProviderStatus.ACTIVE, ModelStatus.ACTIVE);
        ModelProviderId otherId = new ModelProviderId("other");
        ModelDefinition duplicate = model(otherId, ModelStatus.ACTIVE);
        ModelProviderDefinition other = new ModelProviderDefinition(
                otherId,
                "Other",
                "other-adapter",
                URI.create("https://other.example.com"),
                new CredentialRef("env://OTHER_KEY"),
                ProviderStatus.ACTIVE,
                List.of(duplicate),
                Map.of(),
                Map.of());
        assertThatThrownBy(() -> new ImmutableModelCatalog(List.of(first, other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate model id");

        DeterministicModelSelector denied = new DeterministicModelSelector(
                new ImmutableModelCatalog(List.of(first)), (request, provider, model) -> false, "1.0.0");
        assertThatThrownBy(() -> denied.select(request(Set.of(ModelCapability.TEXT_CHAT))))
                .isInstanceOf(ModelSelectionException.class)
                .extracting(value -> ((ModelSelectionException) value).failure())
                .isEqualTo(ModelSelectionFailure.ACCESS_DENIED);
    }

    @Test
    void adapterVersionChangesDigestWhileTransientHealthDoesNot() {
        ModelProviderDefinition provider = provider(ProviderStatus.ACTIVE, ModelStatus.ACTIVE);
        ImmutableModelCatalog catalog = new ImmutableModelCatalog(List.of(provider));
        ModelSelectionRequest request = request(Set.of(ModelCapability.TEXT_CHAT));
        DeterministicModelSelector first =
                new DeterministicModelSelector(catalog, ModelAccessPolicy.allowAll(), "1.0.0");
        DeterministicModelSelector upgraded =
                new DeterministicModelSelector(catalog, ModelAccessPolicy.allowAll(), "1.1.0");
        InMemoryProviderHealthRegistry health = new InMemoryProviderHealthRegistry();

        String before = first.select(request).configurationDigest();
        health.update(new ProviderHealth(
                provider.id(), ProviderHealthStatus.UNAVAILABLE, "test outage", Instant.parse("2026-07-21T00:00:00Z")));
        String after = first.select(request).configurationDigest();

        assertThat(after).isEqualTo(before);
        assertThat(upgraded.select(request).configurationDigest()).isNotEqualTo(before);
        assertThat(health.health(provider.id()).status()).isEqualTo(ProviderHealthStatus.UNAVAILABLE);
    }

    private static ModelSelectionRequest request(Set<ModelCapability> capabilities) {
        return new ModelSelectionRequest(
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                new ModelDefinitionId("deepseek-v4-pro"),
                capabilities);
    }

    private static ModelProviderDefinition provider(ProviderStatus providerStatus, ModelStatus modelStatus) {
        ModelProviderId providerId = new ModelProviderId("deepseek");
        return new ModelProviderDefinition(
                providerId,
                "DeepSeek",
                "openai-compatible",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                providerStatus,
                List.of(model(providerId, modelStatus)),
                Map.of("timeout", 60),
                Map.of());
    }

    private static ModelDefinition model(ModelProviderId providerId, ModelStatus status) {
        return new ModelDefinition(
                new ModelDefinitionId("deepseek-v4-pro"),
                providerId,
                "deepseek-v4-pro",
                "DeepSeek V4 Pro",
                status,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_048_576,
                393_216,
                Map.of("thinking", "disabled"),
                Map.of());
    }
}
