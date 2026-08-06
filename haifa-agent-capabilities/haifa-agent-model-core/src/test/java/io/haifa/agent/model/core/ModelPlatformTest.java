package io.haifa.agent.model.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelPlatformTest {
    private static final TenantRef TENANT = new TenantRef("tenant");

    @Test
    void listsOnlySelectableModelsInDeclarationOrderAsSafeImmutableViews() {
        ModelProviderDefinition first = provider(
                "first",
                "adapter-one",
                ProviderStatus.ACTIVE,
                model("first", "first-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT),
                model("first", "first-disabled", ModelStatus.DISABLED, ModelCapability.TEXT_CHAT),
                model("first", "first-reasoning", ModelStatus.ACTIVE, ModelCapability.REASONING));
        ModelProviderDefinition second = provider(
                "second",
                "adapter-two",
                ProviderStatus.ACTIVE,
                model(
                        "second",
                        "second-chat",
                        ModelStatus.ACTIVE,
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING));
        ModelProviderDefinition disabled = provider(
                "disabled",
                "adapter-three",
                ProviderStatus.DISABLED,
                model("disabled", "disabled-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        ModelProviderDefinition missingAdapter = provider(
                "missing",
                "adapter-missing",
                ProviderStatus.ACTIVE,
                model("missing", "missing-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        StaticModelPlatform platform = platform(
                List.of(first, second, disabled, missingAdapter),
                ModelAccessPolicy.allowAll(),
                Map.of(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0", ModelApiStyles.OPENAI_RESPONSES_ADAPTER, "2.0.0"),
                new InMemoryProviderHealthRegistry());

        List<ModelProviderView> available =
                platform.listAvailable(availability("product-user", "product", Set.of(ModelCapability.TEXT_CHAT)));

        assertThat(available).extracting(view -> view.id().value()).containsExactly("first", "second");
        assertThat(available.get(0).models())
                .extracting(view -> view.id().value())
                .containsExactly("first-chat");
        assertThat(available.get(1).models())
                .extracting(view -> view.id().value())
                .containsExactly("second-chat");
        assertThat(available.get(0).healthStatus()).isEqualTo(ProviderHealthStatus.UNKNOWN);
        assertThat(available.get(0).healthObservedAt()).isEqualTo(Instant.EPOCH);

        String rendered = available.toString();
        assertThat(rendered)
                .doesNotContain(
                        "provider-secret-id",
                        "env://FIRST_SECRET",
                        "https://first.private.example",
                        "private-provider-option",
                        "private-provider-metadata",
                        "private-model-option",
                        "private-model-metadata");
        assertThatThrownBy(() -> available.add(available.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> available.getFirst().models().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() ->
                        available.getFirst().models().getFirst().capabilities().add(ModelCapability.REASONING))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appliesCapabilityAndProductAccessPolicyWithoutProductSpecificTypes() {
        ModelProviderDefinition coding = provider(
                "coding",
                "openai-compatible",
                ProviderStatus.ACTIVE,
                model(
                        "coding",
                        "coding-tools",
                        ModelStatus.ACTIVE,
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING));
        ModelProviderDefinition document = provider(
                "document",
                "openai-compatible",
                ProviderStatus.ACTIVE,
                model("document", "document-text", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        ModelAccessPolicy productPolicy = (request, provider, model) ->
                request.principal().principalType().equals(provider.id().value());
        StaticModelPlatform platform = platform(
                List.of(coding, document),
                productPolicy,
                Map.of(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0"),
                new InMemoryProviderHealthRegistry());

        List<ModelProviderView> codingModels = platform.listAvailable(
                availability("coding-user", "coding", Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING)));
        List<ModelProviderView> documentModels =
                platform.listAvailable(availability("document-user", "document", Set.of(ModelCapability.TEXT_CHAT)));

        assertThat(codingModels).extracting(view -> view.id().value()).containsExactly("coding");
        assertThat(documentModels).extracting(view -> view.id().value()).containsExactly("document");
    }

    @Test
    void selectsExactAdapterVersionForEachProviderAndKeepsDigestStable() {
        ModelProviderDefinition first = provider(
                "first",
                "adapter-one",
                ProviderStatus.ACTIVE,
                model("first", "first-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        ModelProviderDefinition second = provider(
                "second",
                "adapter-two",
                ProviderStatus.ACTIVE,
                model("second", "second-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        Map<String, String> adapterVersions = new LinkedHashMap<>();
        adapterVersions.put(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.2.3");
        adapterVersions.put(ModelApiStyles.OPENAI_RESPONSES_ADAPTER, "4.5.6");
        StaticModelPlatform platform = platform(
                List.of(first, second),
                ModelAccessPolicy.allowAll(),
                adapterVersions,
                new InMemoryProviderHealthRegistry());
        adapterVersions.put(ModelApiStyles.OPENAI_CHAT_ADAPTER, "9.9.9");

        var firstSelection = platform.select(selection("first-chat"));
        var repeatedSelection = platform.select(selection("first-chat"));
        var secondSelection = platform.select(selection("second-chat"));

        assertThat(firstSelection.adapterVersion()).isEqualTo("1.2.3");
        assertThat(secondSelection.adapterVersion()).isEqualTo("4.5.6");
        assertThat(repeatedSelection.configurationDigest()).isEqualTo(firstSelection.configurationDigest());
        assertThat(secondSelection.configurationDigest()).isNotEqualTo(firstSelection.configurationDigest());
    }

    @Test
    void failsDeterministicallyWhenRequestedModelAdapterIsUnavailable() {
        ModelProviderDefinition available = provider(
                "available",
                "adapter-one",
                ProviderStatus.ACTIVE,
                model("available", "available-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        ModelProviderDefinition unavailable = provider(
                "unavailable",
                "adapter-two",
                ProviderStatus.ACTIVE,
                model("unavailable", "unavailable-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        StaticModelPlatform platform = platform(
                List.of(available, unavailable),
                ModelAccessPolicy.allowAll(),
                Map.of(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0"),
                new InMemoryProviderHealthRegistry());

        assertThat(platform.listAvailable(availability("user", "product", Set.of(ModelCapability.TEXT_CHAT))))
                .extracting(view -> view.id().value())
                .containsExactly("available");
        assertThatThrownBy(() -> platform.select(selection("unavailable-chat")))
                .isInstanceOf(ModelSelectionException.class)
                .extracting(error -> ((ModelSelectionException) error).failure())
                .isEqualTo(ModelSelectionFailure.ADAPTER_NOT_AVAILABLE);
    }

    @Test
    void healthChangesOnlyTheSafeSummaryAndNeverRoutesOrChangesSelection() {
        ModelProviderDefinition provider = provider(
                "first",
                "adapter-one",
                ProviderStatus.ACTIVE,
                model("first", "first-chat", ModelStatus.ACTIVE, ModelCapability.TEXT_CHAT));
        InMemoryProviderHealthRegistry health = new InMemoryProviderHealthRegistry();
        StaticModelPlatform platform = platform(
                List.of(provider),
                ModelAccessPolicy.allowAll(),
                Map.of(ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0"),
                health);

        String before = platform.select(selection("first-chat")).configurationDigest();
        Instant observedAt = Instant.parse("2026-07-30T04:00:00Z");
        health.update(new ProviderHealth(
                provider.id(), ProviderHealthStatus.UNAVAILABLE, "private outage detail", observedAt));
        List<ModelProviderView> available =
                platform.listAvailable(availability("user", "product", Set.of(ModelCapability.TEXT_CHAT)));
        String after = platform.select(selection("first-chat")).configurationDigest();

        assertThat(available).hasSize(1);
        assertThat(available.getFirst().healthStatus()).isEqualTo(ProviderHealthStatus.UNAVAILABLE);
        assertThat(available.getFirst().healthObservedAt()).isEqualTo(observedAt);
        assertThat(available.toString()).doesNotContain("private outage detail");
        assertThat(platform.health(provider.id()).detail()).isEqualTo("private outage detail");
        assertThat(after).isEqualTo(before);
    }

    private static StaticModelPlatform platform(
            List<ModelProviderDefinition> providers,
            ModelAccessPolicy accessPolicy,
            Map<String, String> adapterVersions,
            ProviderHealthRegistry health) {
        return new StaticModelPlatform(new ImmutableModelCatalog(providers), accessPolicy, adapterVersions, health);
    }

    private static ModelAvailabilityRequest availability(
            String principalId, String principalType, Set<ModelCapability> capabilities) {
        return new ModelAvailabilityRequest(TENANT, new PrincipalRef(principalId, principalType), capabilities);
    }

    private static ModelSelectionRequest selection(String modelId) {
        return new ModelSelectionRequest(
                TENANT,
                new PrincipalRef("user", "product"),
                new ModelDefinitionId(modelId),
                Set.of(ModelCapability.TEXT_CHAT));
    }

    private static ModelProviderDefinition provider(
            String id, String adapterType, ProviderStatus status, ModelDefinition... models) {
        ModelProviderId providerId = new ModelProviderId(id);
        ApiStyleId style =
                switch (adapterType) {
                    case "adapter-two" -> ModelApiStyles.OPENAI_RESPONSES;
                    case "adapter-missing" -> new ApiStyleId("unsupported-style");
                    default -> ModelApiStyles.OPENAI_CHAT_COMPLETIONS;
                };
        List<ModelDefinition> styledModels = java.util.Arrays.stream(models)
                .map(model -> new ModelDefinition(
                        model.id(),
                        model.version(),
                        model.providerId(),
                        model.providerModelId(),
                        model.displayName(),
                        model.status(),
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens(),
                        model.options(),
                        model.metadata(),
                        style))
                .toList();
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                id + " display",
                URI.create("https://" + id + ".private.example"),
                new CredentialRef("env://" + id.toUpperCase(java.util.Locale.ROOT) + "_SECRET"),
                true,
                status,
                List.of(new ModelApiBindingDefinition(style)),
                styledModels,
                Map.of("private", "private-provider-option"),
                Map.of("private", "private-provider-metadata"));
    }

    private static ModelDefinition model(
            String providerId, String id, ModelStatus status, ModelCapability... capabilities) {
        return new ModelDefinition(
                new ModelDefinitionId(id),
                "model-v1",
                new ModelProviderId(providerId),
                "provider-secret-id-" + id,
                id + " display",
                status,
                EnumSet.copyOf(List.of(capabilities)),
                32_768,
                4_096,
                Map.of("private", "private-model-option"),
                Map.of("private", "private-model-metadata"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
    }
}
